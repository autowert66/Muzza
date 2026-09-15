package com.maloy.muzza.utils.cipher

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CipherWebView private constructor(
    context: Context,
    initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)

    // Single-shot continuation slots. All resumes go through takeAndNull-style helpers so a late
    // or duplicate JS-bridge callback (or a renderer-gone racing a normal resume) can never
    // double-resume and crash inside a @JavascriptInterface method. The sig/n slots additionally
    // carry a per-request id, echoed back by the JS bridge: a late result from a cancelled/
    // abandoned request must never resume the NEXT request's continuation with the wrong value.
    private var initContinuation: Continuation<CipherWebView>? = initContinuation
    private val sigSlot = RequestSlot<String>()
    private val nSlot = RequestSlot<String>()

    /**
     * Single-shot continuation slot with an id-checked take. arm() returns the request id the
     * JS call must echo back; takeIfCurrent(id) ignores late callbacks from superseded
     * requests (the stale-result guard); takeAny() is for renderer-gone/timeout paths, which
     * must clear whatever is pending. Synchronized because JS-bridge callbacks arrive on a
     * WebView-internal thread while onRenderProcessGone/timeouts run on the main thread.
     * The distinct method names are deliberate: same-name overloads made dropping the id a
     * silent, compile-clean way to reintroduce the race.
     */
    private class RequestSlot<T> {
        private var continuation: Continuation<T>? = null
        private var requestId = 0

        @Synchronized
        fun arm(cont: Continuation<T>): Int {
            continuation = cont
            return ++requestId
        }

        @Synchronized
        fun takeIfCurrent(id: Int): Continuation<T>? =
            if (id == requestId) continuation.also { continuation = null } else null

        @Synchronized
        fun takeAny(): Continuation<T>? = continuation.also { continuation = null }
    }

    /**
     * Set once the WebView's renderer process has died (or an evaluate timed out, which on a
     * wedged renderer is indistinguishable). A dead instance must be discarded and recreated —
     * per Android docs a WebView whose render process is gone cannot be reused.
     */
    @Volatile
    var isDead: Boolean = false
        private set

    @Volatile
    private var destroyed = false

    // Init is complete only when the page finished loading AND the EJS solver reported success;
    // evaluateJavascript is unreliable before onPageFinished.
    @Volatile
    private var pageFinished = false

    @Volatile
    private var solverLoaded = false

    @Synchronized
    private fun maybeResumeInit() {
        if (pageFinished && solverLoaded) {
            takeInitContinuation()?.resumeSafely { it.resume(this) }
        }
    }

    @Volatile
    var nFunctionAvailable: Boolean = false
        private set

    @Volatile
    var sigFunctionAvailable: Boolean = false
        private set

    @Volatile
    var discoveredNFuncName: String? = null
        private set

    @Volatile
    var usingHardcodedMode: Boolean = false
        private set

    init {
        Timber.tag(TAG).d("Initializing CipherWebView...")

        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message()
                val src = "${m.sourceId()}:${m.lineNumber()}"

                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> {
                        Timber.tag(TAG).e("JS ERROR: $msg at $src")
                    }
                    ConsoleMessage.MessageLevel.WARNING -> {
                        Timber.tag(TAG).w("JS WARN: $msg at $src")
                    }
                    else -> {
                        Timber.tag(TAG).v("JS LOG: $msg")
                    }
                }
                return super.onConsoleMessage(m)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // The EJS preprocessed player assigns `globalThis.location` (its Node-shim setup); in a
            // real browser that triggers a navigation which replaces this document and destroys the
            // solver functions. Block every page-initiated navigation to keep the loaded document.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Timber.tag(TAG).d("Blocked page navigation: ${request.url}")
                return true
            }

            // evaluateJavascript issued before the first page load finishes is silently dropped by
            // Chromium on a detached WebView, so the instance is only handed to callers once the
            // page is done AND the EJS solver finished its (synchronous) init.
            override fun onPageFinished(view: WebView, url: String?) {
                pageFinished = true
                maybeResumeInit()
            }

            // API 26+ callback (never fires below 26; the withTimeout nets in create()/
            // deobfuscateSignature()/transformN() carry recovery on providers that don't
            // deliver it, e.g. Chromium-61-era WebViews).
            @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Timber.tag(TAG).e(
                    "=== RENDER PROCESS GONE === didCrash=${runCatching { detail.didCrash() }.getOrNull()}"
                )
                onRendererGone("WebView render process gone (didCrash=${runCatching { detail.didCrash() }.getOrNull()})")
                // Consume the event so the framework doesn't kill the app process.
                return true
            }
        }

        Timber.tag(TAG).d("WebView settings configured")
    }

    /**
     * The renderer died (or is treated as dead after a timeout): fail every pending continuation
     * with [CipherRendererGoneException] so create()/decipher fail fast instead of hanging forever
     * on JS-bridge callbacks that will never come (and so CipherDeobfuscator's mutex is released),
     * then destroy the WebView — it cannot be reused after a render-process crash.
     */
    private fun onRendererGone(reason: String) {
        isDead = true
        val e = CipherRendererGoneException(reason)
        takeInitContinuation()?.resumeSafely { it.resumeWithException(e) }
        sigSlot.takeAny()?.resumeSafely { it.resumeWithException(e) }
        nSlot.takeAny()?.resumeSafely { it.resumeWithException(e) }
        destroyWebView()
    }

    // Single-shot take — synchronized because JS-bridge callbacks arrive on a WebView-internal
    // thread while onRenderProcessGone/timeouts run on the main thread.
    @Synchronized
    private fun takeInitContinuation(): Continuation<CipherWebView>? =
        initContinuation.also { initContinuation = null }

    private inline fun <T> T.resumeSafely(block: (T) -> Unit) {
        // A continuation cancelled by withTimeout may already be completed; never let that
        // throw out of a WebView callback.
        runCatching { block(this) }
    }

    /**
     * Loads the solver + player source (written by create() on an IO dispatcher via
     * [writeSolverAssets]) into the WebView. Only the cheap WebView work happens here
     * on Main.
     */
    private fun loadPreparedPlayerJs(cacheDir: File) {
        val html = buildDiscoveryHtml()
        Timber.tag(TAG).d("Discovery HTML built (${html.length} chars)")

        runCatching { webView.resumeTimers() }
        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            html, "text/html", "utf-8", null
        )
        Timber.tag(TAG).d("WebView loading started...")
    }

    private fun buildDiscoveryHtml(): String = """<!DOCTYPE html>
<html><head><script>
function deobfuscateSig(obfuscatedSig, reqId) {
    try {
        var func = window._cipherSigFunc;
        if (typeof func !== 'function') {
            CipherBridge.onSigError(reqId, "Sig func not available (type=" + typeof func + ")");
            return;
        }

        var result = func(obfuscatedSig);

        if (result === undefined || result === null) {
            CipherBridge.onSigError(reqId, "Sig func returned null/undefined");
            return;
        }
        CipherBridge.onSigResult(reqId, String(result));
    } catch (error) {
        CipherBridge.onSigError(reqId, error + "\n" + (error.stack || ""));
    }
}

function transformN(nValue, reqId) {
    CipherBridge.logDebug("transformN called: nValue=" + nValue + ", reqId=" + reqId);

    try {
        var func = window._nTransformFunc;
        CipherBridge.logDebug("window._nTransformFunc type: " + typeof func);

        if (typeof func !== 'function') {
            CipherBridge.onNError(reqId, "N-transform func not available (type: " + typeof func + ")");
            return;
        }

        var result = func(nValue);
        CipherBridge.logDebug("N-transform raw result: " + (result ? String(result).substring(0, 50) : "null/undefined"));

        if (result === undefined || result === null) {
            CipherBridge.onNError(reqId, "N-transform returned null/undefined");
            return;
        }

        var resultStr = String(result);
        CipherBridge.logDebug("N-transform result: length=" + resultStr.length + ", value=" + resultStr.substring(0, 30));
        CipherBridge.onNResult(reqId, resultStr);
    } catch (error) {
        CipherBridge.onNError(reqId, error + "\n" + (error.stack || ""));
    }
}

function initSolver() {
    try {
        var solver = (typeof jsc === 'function') ? jsc : (jsc && jsc.default);
        if (typeof solver !== 'function') { throw new Error("jsc solver not loaded"); }
        if (typeof window._yt_player_source !== 'string') { throw new Error("player source not loaded"); }

        // yt-dlp EJS: parse player.js and statically extract the sig/n solver functions.
        // Preprocess once, then materialize the functions for reuse on every challenge.
        var out = solver({ type: "player", player: window._yt_player_source, requests: [], output_preprocessed: true });
        if (!out || out.type !== "result" || typeof out.preprocessed_player !== "string") {
            throw new Error("EJS preprocess failed: " + JSON.stringify(out));
        }

        var resultObj = { n: null, sig: null };
        (new Function("_result", out.preprocessed_player))(resultObj);

        window._cipherSigFunc = resultObj.sig;
        window._nTransformFunc = resultObj.n;

        var sigName = (typeof resultObj.sig === 'function') ? "ejs_sig" : "";
        var nName = "";
        if (typeof resultObj.n === 'function') {
            var testInput = "KdrqFlzJXl9EcCwlmEy";
            var testResult = resultObj.n(testInput);
            if (typeof testResult === 'string' && testResult !== testInput && testResult.length >= 5 && /^[a-zA-Z0-9_-]+$/.test(testResult)) {
                nName = "ejs_n";
            } else {
                window._nTransformFunc = null;
            }
        }
        CipherBridge.onDiscoveryDone(sigName, nName, "ejs");
    } catch (e) {
        CipherBridge.onPlayerJsError((e && e.stack) ? e.stack : String(e));
        return;
    }
    CipherBridge.onPlayerJsLoaded();
}
</script>
<script src="solver.js"></script>
<script src="player_source.js"
    onerror="CipherBridge.onPlayerJsError('Failed to load player source from file')"></script>
<script>initSolver();</script>
</head><body></body></html>"""

    @JavascriptInterface
    fun logDebug(message: String) {
        Timber.tag(TAG).d("JS: $message")
    }

    @JavascriptInterface
    fun onDiscoveryDone(sigFuncName: String, nFuncName: String, info: String) {
        Timber.tag(TAG).d("=== DISCOVERY COMPLETE ===")
        Timber.tag(TAG).d("Sig function: ${sigFuncName.ifEmpty { "NOT FOUND" }}")
        Timber.tag(TAG).d("N function: ${nFuncName.ifEmpty { "NOT FOUND" }}")
        Timber.tag(TAG).d("Info: $info")

        sigFunctionAvailable = sigFuncName.isNotEmpty()
        if (nFuncName.isNotEmpty()) {
            discoveredNFuncName = nFuncName
            nFunctionAvailable = true
            Timber.tag(TAG).d("N-function AVAILABLE: $nFuncName")
        } else {
            Timber.tag(TAG).e("N-function NOT AVAILABLE")
            nFunctionAvailable = false
        }
    }

    @JavascriptInterface
    fun onNDiscoveryDone(funcName: String, info: String) {
        Timber.tag(TAG).d("Legacy onNDiscoveryDone: funcName=$funcName, info=$info")
        if (funcName.isNotEmpty()) {
            discoveredNFuncName = funcName
            nFunctionAvailable = true
        }
    }

    @JavascriptInterface
    fun onPlayerJsLoaded() {
        Timber.tag(TAG).d("=== PLAYER.JS LOAD COMPLETE ===")
        Timber.tag(TAG).d("sigFunctionAvailable=$sigFunctionAvailable")
        Timber.tag(TAG).d("nFunctionAvailable=$nFunctionAvailable")
        Timber.tag(TAG).d("discoveredNFuncName=$discoveredNFuncName")
        Timber.tag(TAG).d("usingHardcodedMode=$usingHardcodedMode")

        solverLoaded = true
        maybeResumeInit()
    }

    @JavascriptInterface
    fun onPlayerJsError(error: String) {
        Timber.tag(TAG).e("=== PLAYER.JS LOAD FAILED ===")
        Timber.tag(TAG).e("Error: $error")
        takeInitContinuation()?.resumeSafely {
            it.resumeWithException(CipherException("Player JS load failed: $error"))
        }
    }

    suspend fun deobfuscateSignature(obfuscatedSig: String): String {
        Timber.tag(TAG).d("========== DEOBFUSCATE SIGNATURE ==========")
        Timber.tag(TAG).d("Input sig length: ${obfuscatedSig.length}")
        Timber.tag(TAG).d("Input sig preview: ${obfuscatedSig.take(50)}...")
        if (!sigFunctionAvailable) {
            Timber.tag(TAG).e("Signature function not available")
            throw CipherException("Signature function not available")
        }
        throwIfDead()

        return try {
            withTimeout(EVAL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val requestId = sigSlot.arm(cont)
                        val jsCall = "deobfuscateSig('${escapeJsString(obfuscatedSig)}', $requestId)"
                        webView.evaluateJavascript(jsCall, null)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A renderer that never answers evaluateJavascript is wedged/dead — safety net for
            // providers where onRenderProcessGone doesn't fire.
            Timber.tag(TAG).e("Sig deobfuscation timed out after ${EVAL_TIMEOUT_MS}ms — treating renderer as gone")
            failAsRendererGone("Sig deobfuscation timed out after ${EVAL_TIMEOUT_MS}ms")
        }
    }

    @JavascriptInterface
    fun onSigResult(requestId: Int, result: String) {
        Timber.tag(TAG).d("========== SIGNATURE RESULT ==========")
        Timber.tag(TAG).d("Result length: ${result.length} (requestId=$requestId)")
        Timber.tag(TAG).d("Result preview: ${result.take(50)}...")
        sigSlot.takeIfCurrent(requestId)?.resumeSafely { it.resume(result) }
    }

    @JavascriptInterface
    fun onSigError(requestId: Int, error: String) {
        Timber.tag(TAG).e("========== SIGNATURE ERROR ==========")
        Timber.tag(TAG).e("Error: $error (requestId=$requestId)")
        sigSlot.takeIfCurrent(requestId)?.resumeSafely {
            it.resumeWithException(CipherException("Sig deobfuscation failed: $error"))
        }
    }

    suspend fun transformN(nValue: String): String {
        Timber.tag(TAG).d("========== N-TRANSFORM ==========")
        Timber.tag(TAG).d("Input n value: $nValue")
        Timber.tag(TAG).d("nFunctionAvailable: $nFunctionAvailable")
        Timber.tag(TAG).d("discoveredNFuncName: $discoveredNFuncName")

        if (!nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function not discovered")
            throw CipherException("N-transform function not discovered")
        }
        throwIfDead()

        return try {
            withTimeout(EVAL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val requestId = nSlot.arm(cont)
                        val jsCall = "transformN('${escapeJsString(nValue)}', $requestId)"
                        Timber.tag(TAG).d("Evaluating JS: $jsCall")
                        webView.evaluateJavascript(jsCall, null)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("N-transform timed out after ${EVAL_TIMEOUT_MS}ms — treating renderer as gone")
            failAsRendererGone("N-transform timed out after ${EVAL_TIMEOUT_MS}ms")
        }
    }

    @JavascriptInterface
    fun onNResult(requestId: Int, result: String) {
        Timber.tag(TAG).d("========== N-TRANSFORM RESULT ==========")
        Timber.tag(TAG).d("Result: $result (requestId=$requestId)")
        Timber.tag(TAG).d("Result length: ${result.length}")
        nSlot.takeIfCurrent(requestId)?.resumeSafely { it.resume(result) }
    }

    @JavascriptInterface
    fun onNError(requestId: Int, error: String) {
        Timber.tag(TAG).e("========== N-TRANSFORM ERROR ==========")
        Timber.tag(TAG).e("Error: $error (requestId=$requestId)")
        nSlot.takeIfCurrent(requestId)?.resumeSafely {
            it.resumeWithException(CipherException("N-transform failed: $error"))
        }
    }

    private fun throwIfDead() {
        if (isDead) {
            throw CipherRendererGoneException("CipherWebView renderer is gone — instance must be recreated")
        }
    }

    /** Timeout path: mark this instance dead, clear pending slots, throw renderer-gone. */
    private fun failAsRendererGone(reason: String): Nothing {
        isDead = true
        sigSlot.takeAny()
        nSlot.takeAny()
        throw CipherRendererGoneException(reason)
    }

    fun close() {
        Timber.tag(TAG).d("Closing CipherWebView...")
        destroyWebView()
        Timber.tag(TAG).d("CipherWebView closed")
    }

    private fun destroyWebView() {
        if (destroyed) return
        destroyed = true
        // After a render-process crash some WebView methods can throw — never let teardown crash.
        runCatching {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        }.onFailure { Timber.tag(TAG).w("WebView teardown threw: $it") }
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private const val TAG = "Muzza_CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"

        // Loaded in order into one file: the EJS bundle takes meriyah + astring as IIFE args.
        private val SOLVER_ASSETS = listOf(
            "solver/meriyah.js",
            "solver/astring.js",
            "solver/yt.solver.core.js",
        )

        // Loading + parsing ~2.8 MB player.js on a slow device takes seconds; a renderer that
        // hasn't answered after this long is dead or wedged (observed OOM kills happen ~1.2 s in).
        private const val CREATE_TIMEOUT_MS = 30_000L

        // A live renderer answers sig/n evaluate calls in milliseconds; this only fires when the
        // renderer died without onRenderProcessGone being delivered (old providers).
        private const val EVAL_TIMEOUT_MS = 15_000L

        /**
         * Writes the cipher assets the WebView needs: one concatenated JS file with meriyah +
         * astring + the yt-dlp EJS core, and the raw player.js source exposed as the JS string
         * global `window._yt_player_source`. This scans/copies a ~3 MB string — it MUST run off
         * the main thread (create() calls it on Dispatchers.IO before any WebView work).
         */
        private fun writeSolverAssets(context: Context, cacheDir: File, playerJs: String) {
            val solverJs = buildString {
                for (asset in SOLVER_ASSETS) {
                    context.assets.open(asset).bufferedReader().use { append(it.readText()) }
                    append('\n')
                }
            }
            File(cacheDir, "solver.js").writeText(solverJs)
            File(cacheDir, "player_source.js").writeText(
                "window._yt_player_source = " + JSONObject.quote(playerJs) + ";"
            )
            Timber.tag(TAG).d(
                "Solver assets written: solver.js=${solverJs.length} chars, " +
                    "player source=${playerJs.length} chars"
            )
        }

        suspend fun create(
            context: Context,
            playerJs: String,
        ): CipherWebView {
            Timber.tag(TAG).d("=== CREATING CIPHER WEBVIEW ===")
            Timber.tag(TAG).d("playerJs size: ${playerJs.length} chars")

            // Heavy prep (multi-MB string write) runs on IO; only WebView construction and the
            // load call happen on the main thread below.
            val cacheDir = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "cipher")
                dir.mkdirs()
                writeSolverAssets(context, dir, playerJs)
                dir
            }

            var created: CipherWebView? = null
            try {
                return withTimeout(CREATE_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val wv = CipherWebView(context, cont)
                            created = wv
                            wv.loadPreparedPlayerJs(cacheDir)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.tag(TAG).e("CipherWebView init timed out after ${CREATE_TIMEOUT_MS}ms — treating renderer as gone")
                destroyQuietly(created)
                throw CipherRendererGoneException("CipherWebView init timed out after ${CREATE_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                // Covers both caller cancellation (CancellationException is rethrown, never
                // swallowed) and init failure via an error resume (e.g. onPlayerJsError ->
                // CipherException): destroy the half-initialized WebView either way, or every
                // failed create() leaks a live renderer that the retry path then multiplies.
                destroyQuietly(created)
                throw e
            }
        }

        private suspend fun destroyQuietly(wv: CipherWebView?) {
            if (wv == null) return
            withContext(NonCancellable + Dispatchers.Main) {
                wv.isDead = true
                wv.takeInitContinuation() // never resume a cancelled continuation later
                wv.destroyWebView()
            }
        }
    }
}

class CipherException(message: String) : Exception(message)

/**
 * The cipher WebView's render process died (kernel OOM kill, crash) or stopped responding.
 * The instance is unusable; callers must drop it and decide whether recreating is worth it
 * (see [RendererRecoveryPolicy]).
 */
class CipherRendererGoneException(message: String) : Exception(message)
