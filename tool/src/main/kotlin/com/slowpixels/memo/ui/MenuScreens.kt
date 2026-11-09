package com.slowpixels.memo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.slowpixels.memo.Hub
import com.slowpixels.memo.Version
import com.slowpixels.memo.deck.Deck
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

class MenuScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val state by Hub.deck.state.collectAsState()
        ListPage("Menu", back = { goBack() }) {
            Item("Play speed", value = Say.speed(state.speed) + if (!state.keepPitch && state.speed != 1f) ", like tape" else "") {
                navigateTo(::SpeedScreen)
            }
            Item("Help") { navigateTo(::HelpScreen) }
            Item("About") { navigateTo(::AboutScreen) }
        }
    }
}

/** How fast memos play, and whether a memo keeps its pitch or goes up and down with the speed as tape did. */
class SpeedScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val state by Hub.deck.state.collectAsState()
        ListPage("Play speed", back = { goBack() }) {
            for (x in Deck.SPEEDS) Choice(Say.speed(x) + if (x == 1f) " (normal)" else "", selected = state.speed == x) { Hub.deck.setSpeed(x) }
            Heading("At other speeds")
            Choice("Keep pitch", selected = state.keepPitch, detail = "A memo sounds like itself") { Hub.deck.setKeepPitch(true) }
            Choice("Like tape", selected = !state.keepPitch, detail = "Slower is lower, faster is higher") { Hub.deck.setKeepPitch(false) }
        }
    }
}

class HelpScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        ListPage("Help", back = { goBack() }) {
            Heading("Record")
            Item("The big key", detail = "Press it to start a memo; press it again to stop and keep it")
            Item("Keep Memo open", detail = "A tool can only use the mic while it's on screen, so the screen stays on while you record. Leaving Memo stops and keeps the memo")
            Item("A call", detail = "Stops the memo and keeps it")
            Heading("Listen")
            Item("Play and stop", detail = "Stop leaves the memo where it is; play goes on from there")
            Item("Back and forward", detail = "While playing, five seconds a tap; hold to go further. While stopped, to the start, or the memo before or after")
            Item("The bar", detail = "Touch or slide along it to move through the memo")
            Item("The title", detail = "Tap it to rename or erase the memo")
            Item("Locked", detail = "A memo goes on playing with the phone locked")
            Heading("Memos")
            Item("On a computer", detail = "Light's Tool Manager shows Memo's folder of memos, to copy them off the phone or add sound files")
            Item("Erase several", detail = "Memos, then SELECT")
        }
    }
}

class AboutScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        ListPage("About", back = { goBack() }) {
            val gap = Modifier.padding(bottom = 1f.gridUnitsAsDp())
            LightText("A pocket memo recorder. Slide to Record and press the big key; slide to Listen to play memos back, at up to twice the speed or half, with their own pitch or with tape's.", LightTextVariant.Paragraph, gap)
            LightText("Memos stay on the phone as AAC sound files. The tool can't reach the internet, and asks for the microphone and nothing else. Light's Tool Manager can copy memos to a computer, and back them up if you turn that on.", LightTextVariant.Paragraph, gap)
            LightText("Version ${Version.NAME}", LightTextVariant.Paragraph, lighten = true)
            LightText("Made by Slow Pixels", LightTextVariant.Paragraph, lighten = true)
        }
    }
}
