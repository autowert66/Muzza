package com.maloy.muzza.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun rememberPredictiveBackProgress(
    enabled: Boolean,
    onBack: () -> Unit,
): State<Float> {
    val progress = remember { Animatable(0f) }
    if (enabled) {
        PredictiveBackHandler { backProgress ->
            try {
                backProgress.collect { backEvent ->
                    progress.snapTo(backEvent.progress.coerceIn(0f, 1f))
                }
                onBack()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
                throw e
            }
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled && progress.value != 0f) {
            progress.snapTo(0f)
        }
    }
    return progress.asState()
}

fun Modifier.predictiveBackExit(progress: State<Float>): Modifier = graphicsLayer {
    alpha = 1f - progress.value
    translationY = -progress.value * size.height
}
