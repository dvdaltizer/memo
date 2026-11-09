package com.slowpixels.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred

/**
 * A few words for a moment, just above the bar along the foot of the screen, over whatever's showing. It takes no
 * touches, so the keys under it still work while it's up.
 */
class Notice(private val text: String) : LightModal {
    private val gone = CompletableDeferred<Unit>()
    override val onExpired: () -> Unit = {}
    override fun dismiss() { gone.complete(Unit) }
    override suspend fun awaitDismiss() { gone.await() }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier.fillMaxWidth().background(colors.background)
                        .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.content))
                    LightText(text, LightTextVariant.Copy, Modifier.fillMaxWidth().padding(vertical = 0.6f.gridUnitsAsDp()), align = TextAlign.Center)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.content))
                }
                Spacer(Modifier.height(4f.gridUnitsAsDp()))
            }
        }
    }

    companion object {
        fun say(text: String) = LightModalManager.show(Notice(text), 2800.milliseconds)
    }
}
