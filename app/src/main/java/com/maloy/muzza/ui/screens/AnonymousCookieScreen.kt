package com.maloy.muzza.ui.screens

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.maloy.innertube.YouTube
import com.maloy.muzza.LocalPlayerAwareWindowInsets
import com.maloy.muzza.R
import com.maloy.muzza.constants.AudioQuality
import com.maloy.muzza.constants.InnerTubeCookieKey
import com.maloy.muzza.constants.VisitorDataKey
import com.maloy.muzza.ui.component.IconButton
import com.maloy.muzza.ui.utils.backToMain
import com.maloy.muzza.utils.YTPlayerUtils
import com.maloy.muzza.utils.rememberPreference
import com.maloy.muzza.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val YT_MUSIC_URL = "https://music.youtube.com/"

// A few well-known tunes that exist on YouTube Music in virtually every region. The live check
// passes if any of them returns a playable response with the captured cookies attached.
private val VERIFICATION_SONGS = listOf(
    "4NRXx6U8ABQ", // The Weeknd - Blinding Lights
    "kJQP7kiw5Fk", // Luis Fonsi - Despacito
    "JGwWNGJdvx8", // Ed Sheeran - Shape of You
)

private enum class SaveState {
    IDLE,
    TESTING,
    SUCCESS,
    FAILED,
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnonymousCookieScreen(
    navController: NavController,
) {
    var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    var visitorData by rememberPreference(VisitorDataKey, "")
    val scope = rememberCoroutineScope()

    var capturedCookie by remember { mutableStateOf<String?>(null) }
    var capturedVisitorData by remember { mutableStateOf<String?>(null) }
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var webView: WebView? = null

    val connectivityManager = LocalContext.current.getSystemService(ConnectivityManager::class.java)

    val captureCookies = {
        val cookie = CookieManager.getInstance().getCookie(YT_MUSIC_URL)
        if (!cookie.isNullOrBlank()) {
            capturedCookie = cookie
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            if (url.contains("music.youtube.com")) {
                                captureCookies()
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url?.contains("music.youtube.com") == true) {
                                captureCookies()
                            }
                            view.loadUrl("javascript:(function(){try{if(window.yt&&window.yt.config_&&window.yt.config_.VISITOR_DATA){Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA);}}catch(e){}})()")
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (!newVisitorData.isNullOrBlank() && newVisitorData != "null") {
                                capturedVisitorData = newVisitorData
                            }
                        }
                    }, "Android")
                    webView = this
                    loadUrl(YT_MUSIC_URL)
                }
            }
        )

        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.get_cookies)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (saveState) {
                        SaveState.SUCCESS -> stringResource(R.string.anonymous_cookie_success)
                        SaveState.FAILED -> stringResource(R.string.anonymous_cookie_failed)
                        else -> stringResource(R.string.anonymous_cookie_play_instruction)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (saveState) {
                        SaveState.SUCCESS -> MaterialTheme.colorScheme.primary
                        SaveState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saveState == SaveState.TESTING) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else {
                        Button(
                            enabled = saveState != SaveState.SUCCESS,
                            onClick = {
                                val cookie = capturedCookie
                                if (cookie.isNullOrBlank()) {
                                    saveState = SaveState.FAILED
                                    return@Button
                                }
                                saveState = SaveState.TESTING
                                scope.launch {
                                    val previousCookie = YouTube.cookie
                                    val previousVisitorData = YouTube.visitorData
                                    val works = withContext(Dispatchers.IO) {
                                        try {
                                            YouTube.cookie = cookie
                                            capturedVisitorData?.let { YouTube.visitorData = it }
                                            verifyAnonymousPlayback(connectivityManager)
                                        } catch (e: Exception) {
                                            reportException(e)
                                            false
                                        }
                                    }
                                    if (works) {
                                        innerTubeCookie = cookie
                                        capturedVisitorData?.let { visitorData = it }
                                        YouTube.useLoginForBrowse = true
                                        saveState = SaveState.SUCCESS
                                        delay(600)
                                        navController.navigateUp()
                                    } else {
                                        YouTube.cookie = previousCookie
                                        YouTube.visitorData = previousVisitorData
                                        saveState = SaveState.FAILED
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.anonymous_cookie_save))
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

private suspend fun verifyAnonymousPlayback(connectivityManager: ConnectivityManager): Boolean {
    for (videoId in VERIFICATION_SONGS) {
        val result = YTPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            playlistId = null,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager,
        )
        if (result.isSuccess) {
            return true
        }
    }
    return false
}
