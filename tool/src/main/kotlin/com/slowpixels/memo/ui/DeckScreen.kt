package com.slowpixels.memo.ui

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import com.slowpixels.memo.Hub
import com.slowpixels.memo.deck.Mode
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.PermissionRequestLauncher
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.launch

/** Lives as long as the deck screen: joins Light's player when it's made, and lets go when the tool is left for good. */
class DeckViewModel(audio: LightAudio, filesDir: File) : LightViewModel<Unit>() {
    init { Hub.attach(this, audio, filesDir) }

    override fun onCleared() {
        Hub.leave(this)
        super.onCleared()
    }
}

/**
 * The recorder: one screen, nothing to scroll. The top bar goes to the memos and the menu, the face shows what the deck
 * is doing, the mode's keys sit under it, and the mode switch runs along the foot. The screen stays on while recording,
 * since a tool can only use the mic while it's on screen.
 */
@InitialScreen
class DeckScreen(private val sealed: SealedLightActivity) : LightScreen<Unit, DeckViewModel>(sealed) {
    override val viewModelClass = DeckViewModel::class.java
    override fun createViewModel() = DeckViewModel(DefaultLightAudio(sealed), lightContext.filesDir)

    /**
     * Only while on screen. If the keep-on flag is set when a window comes back from the background, Android treats it
     * as the window's own and it can't be cleared, so the phone would stay awake after recording had stopped.
     */
    private val awake = mutableStateOf(false)
    private var launcher: PermissionRequestLauncher? = null

    override fun willShow() {
        viewModel
        Hub.closeUntil = null
        awake.value = true
        Hub.deck.watching(true)
        ask()
    }

    override fun willHide() {
        awake.value = false
        Hub.deck.watching(false)
    }

    override fun onAppPause() {
        awake.value = false
        Hub.deck.watching(false)
        Hub.deck.paused()
    }

    /** Asks LightOS whether the mic is allowed. The ask comes back after Light's permission screen too. */
    private fun ask() {
        Hub.scope.launch { Hub.micOk.value = micAllowed() }
    }

    private suspend fun micAllowed() = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)

    /** Runs [then] if the mic is allowed; otherwise opens Light's permission screen, and asks again on coming back. */
    private fun withMic(then: () -> Unit) {
        when (Hub.micOk.value) {
            true -> then()
            false -> launcher?.launch()
            null -> Hub.scope.launch {
                val ok = micAllowed()
                Hub.micOk.value = ok
                if (ok) then() else launcher?.launch()
            }
        }
    }

    /** A recording mustn't be cut short by a stray tap at the top of the screen. The display says why nothing happened. */
    private fun notRecording(then: () -> Unit) {
        if (Hub.deck.state.value.mode == Mode.Recording) Hub.deck.say("Stop recording first") else then()
    }

    @Composable
    override fun Content() {
        viewModel
        launcher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val deck = Hub.deck
        val state by deck.state.collectAsState()
        val list by Hub.memos.list.collectAsState()
        val level = deck.level.collectAsState()
        val memo = list.firstOrNull { it.number == state.memo }
        val recording = state.mode == Mode.Recording
        val next = if (recording) state.memo ?: deck.recordNumber else deck.recordNumber
        val title = when {
            !state.listening -> "Memo $next"
            memo != null -> memo.title
            else -> "Memo"
        }
        val keep = awake.value && recording
        Themed {
            Box(Modifier.fillMaxSize().then(if (keep) Modifier.keepScreenOn() else Modifier)) {
                Column(Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.Text("MEMOS", onClick = { notRecording { navigateTo(::MemosScreen) } }),
                        center = LightTopBarCenter.Text(
                            title,
                            onClick = if (state.listening && memo != null) ({ navigateTo(::MemoScreen) }) else null,
                        ),
                        rightButton = LightBarButton.Text("MENU", onClick = { notRecording { navigateTo(::MenuScreen) } }),
                    )
                    Face(state, memo, level, deck.recordNumber, Modifier.weight(1f).fillMaxWidth(), onSeek = deck::seek)
                    val keys = Modifier.fillMaxWidth().height(8.5f.gridUnitsAsDp())
                    if (!state.listening) {
                        RecordKey(recording, sealed.vibrator, keys) {
                            if (deck.state.value.mode == Mode.Recording) deck.record() else withMic { deck.record() }
                        }
                    } else {
                        Transport(
                            playing = state.mode == Mode.Playing, enabled = memo != null, vibrator = sealed.vibrator, modifier = keys,
                            onBack = deck::back, onStop = deck::stop, onPlay = deck::play, onAhead = deck::ahead,
                        )
                    }
                    ModeSwitch(state.listening, sealed.vibrator) { deck.listen(it) }
                }
            }
        }
    }
}
