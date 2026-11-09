package com.slowpixels.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.slowpixels.memo.Hub
import com.slowpixels.memo.deck.Mode
import com.slowpixels.memo.store.Memos
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

/** Every memo, newest first. The one on the deck is underlined; a tap puts a memo on the deck and plays it. */
class MemosScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    override val tag = "memos"

    @Composable
    override fun Body() {
        val list by Hub.memos.list.collectAsState()
        val state by Hub.deck.state.collectAsState()
        val newest = list.asReversed()
        ListPage(
            "Memos", back = { goBack() },
            right = if (list.isNotEmpty()) LightBarButton.Text("SELECT", onClick = { navigateTo(::PickScreen) }) else null,
        ) {
            if (newest.isEmpty()) Note("No memos yet. Slide the switch to Record, then press the big key.")
            for (m in newest) {
                Choice(m.title, selected = state.listening && m.number == state.memo, caps = false, detail = Say.line(m.made, m.length)) {
                    Hub.deck.select(m.number, andPlay = true)
                    toDeck()
                }
            }
        }
    }
}

/** Picking memos to erase: a tap marks one (underlined) or unmarks it. */
class PickScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    override val tag = "pick"
    private val picked = mutableStateOf(emptySet<Int>())

    @Composable
    override fun Body() {
        val list by Hub.memos.list.collectAsState()
        val chosen = picked.value.filterTo(HashSet()) { n -> list.any { it.number == n } }
        val count = chosen.size
        ListPage(
            if (count == 0) "Select memos" else "$count selected", back = { goBack() },
            bottom = listOf(
                LightBarButton.Text("CANCEL", onClick = { goBack() }),
                LightBarButton.Text("ERASE", enabled = count > 0, onClick = {
                    if (count > 0) navigateTo({ EraseScreen(it, chosen) })
                }),
            ),
        ) {
            for (m in list.asReversed()) {
                val on = m.number in chosen
                Choice(m.title, selected = on, caps = false, detail = Say.line(m.made, m.length)) {
                    picked.value = if (on) picked.value - m.number else picked.value + m.number
                }
            }
        }
    }
}

/** Asks before memos go for good. */
class EraseScreen(sealed: SealedLightActivity, private val numbers: Set<Int>) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val all by Hub.memos.list.collectAsState()
        val state by Hub.deck.state.collectAsState()
        // Said as it was asked, so the page doesn't change under the finger as the memos go.
        val count = remember { all.count { it.number in numbers } }
        val what = remember { all.singleOrNull { it.number in numbers }?.title ?: "$count memos" }
        val recording = state.mode == Mode.Recording
        AskPage("Erase $what?", listOf(if (count == 1) "It goes for good, and can't be got back." else "They go for good, and can't be got back."),
            yes = "ERASE", back = { goBack() }) {
            if (!recording) Hub.deck.erase(numbers)
            Hub.say(if (count == 1) "Erased" else "Erased $count memos")
            if (Hub.memos.list.value.isEmpty()) toDeck() else { Hub.closeUntil = "memos"; goBack() }
        }
    }
}

/** The memo on the deck: when it was made, how long it is, and what can be done with it. */
class MemoScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val list by Hub.memos.list.collectAsState()
        val state by Hub.deck.state.collectAsState()
        val m = list.firstOrNull { it.number == state.memo }
        if (m == null) { ListPage("Memo", back = { goBack() }) { Note("This memo is gone.") }; return }
        ListPage(m.title, back = { goBack() }) {
            Item("Made", value = Say.date(m.made))
            Item("Length", value = Say.length(m.length))
            Item("Number", value = "${m.number}")
            Item("File", detail = m.file)
            Heading("This memo")
            Item("Rename") {
                navigateTo({ NameScreen(it, "Rename", m.name, placeholder = "Memo ${m.number}") { name -> Hub.memos.rename(m.number, name) } })
            }
            Item("Erase", detail = "Gone for good") { navigateTo({ EraseScreen(it, setOf(m.number)) }) }
        }
    }
}

/** A name typed on Light's keyboard. [onDone] gets what was typed, trimmed, which may be nothing (the number again). */
class NameScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val placeholder: String = "",
    private val onDone: (String) -> Unit,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val options = rememberKeyboardOptions()
        val text = rememberTextFieldState(initial)
        Themed {
            LightTextInputEditor(
                title = if (text.text.isEmpty() && placeholder.isNotEmpty()) "$title: $placeholder" else title,
                state = text, keyboardOptionsFlow = options, submitLabel = "SAVE", singleLine = true, initialCaps = true,
                onSubmit = { onDone(it.toString().trim().take(Memos.MAX_NAME)); goBack() },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
