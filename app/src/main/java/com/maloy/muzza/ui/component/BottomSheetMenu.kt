package com.maloy.muzza.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.maloy.muzza.ui.utils.top
import kotlin.coroutines.cancellation.CancellationException

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)

    fun show(content: @Composable ColumnScope.() -> Unit) {
        isVisible = true
        this.content = content
    }

    fun dismiss() {
        isVisible = false
    }
}

@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val focusManager = LocalFocusManager.current
    val menuProgress = remember { Animatable(if (state.isVisible) 1f else 0f) }
    val progress by menuProgress.asState()
    val animationSpec = remember { spring<Float>(stiffness = Spring.StiffnessMediumLow) }
    val visible = state.isVisible || progress > 0f

    LaunchedEffect(state.isVisible) {
        if (state.isVisible) {
            focusManager.clearFocus()
        }
        menuProgress.animateTo(if (state.isVisible) 1f else 0f, animationSpec)
    }

    if (state.isVisible) {
        PredictiveBackHandler { backProgress ->
            try {
                backProgress.collect { backEvent ->
                    menuProgress.snapTo(1f - backEvent.progress.coerceIn(0f, 1f))
                }
                state.dismiss()
            } catch (e: CancellationException) {
                menuProgress.animateTo(1f, animationSpec)
                throw e
            }
        }
    }

    if (visible) {
        Spacer(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures {
                        state.dismiss()
                    }
                }
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f * progress))
                .fillMaxSize()
        )
    }

    if (visible) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - progress) * size.height
                    alpha = progress
                }
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .padding(top = 48.dp)
                .clip(ShapeDefaults.Large.top())
                .background(background)
        ) {
            state.content(this)
        }
    }
}