package com.slowpixels.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.slowpixels.memo.Hub
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A list screen over the deck. Some choices take every list down to a screen below ([Hub.closeUntil]): each list
 * leaves by itself when it next shows, unless it's the one asked for. The kit draws every screen from the same spot,
 * so what's remembered here is keyed on the screen itself.
 */
abstract class ListScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    /** Names this screen for [Hub.closeUntil]. */
    open val tag = ""
    private val shown = mutableIntStateOf(0)

    override fun willShow() { shown.intValue++ }

    @Composable
    override fun Content() {
        val n = shown.intValue
        LaunchedEffect(this, n) {
            val until = Hub.closeUntil
            if (until != null) { if (until == tag) Hub.closeUntil = null else goBack() }
        }
        key(this) { Body() }
    }

    @Composable
    abstract fun Body()

    /** Back to the deck, closing every list on the way. */
    fun toDeck() {
        Hub.closeUntil = "deck"
        goBack()
    }
}

/** LightOS's theme on a full screen. */
@Composable
fun Themed(content: @Composable ColumnScope.() -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background), content = content)
    }
}

/** A sub-screen's top: a back arrow and title. */
@Composable
fun BackBar(title: String, back: () -> Unit, right: LightBarButton? = null) = LightTopBar(
    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = back),
    center = LightTopBarCenter.Text(title),
    rightButton = right,
    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
)

/** A sub-screen: a back arrow and title over a scrolling list, and a bar along the foot if [bottom] is given. */
@Composable
fun ListPage(
    title: String,
    back: () -> Unit,
    right: LightBarButton? = null,
    bottom: List<LightBarButton?>? = null,
    rows: @Composable ColumnScope.() -> Unit,
) = Themed {
    BackBar(title, back, right)
    LightScrollView(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()), content = rows)
    if (bottom != null) LightBottomBar(bottom)
}

/** A yes-or-no page: a question, a line or two under it, and the two answers along the foot. */
@Composable
fun AskPage(title: String, lines: List<String>, yes: String, back: () -> Unit, onYes: () -> Unit) = Themed {
    BackBar(title, back)
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) { for (l in lines) Note(l) }
    LightBottomBar(listOf(LightBarButton.Text("CANCEL", onClick = back), LightBarButton.Text(yes, onClick = onYes)))
}

/** One choice in a list; the one in use is underlined, as LightOS's own settings lists do. */
@Composable
fun Choice(label: String, selected: Boolean = false, detail: String? = null, caps: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().lightClickable(onClick = onClick).semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(if (caps) label.uppercase() else label, LightTextVariant.Copy, underline = selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) LightText(detail, LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row: what it is on the left and, if given, what it's set to on the right. Tappable when [onClick] is given. */
@Composable
fun Item(label: String, value: String? = null, detail: String? = null, caps: Boolean = true, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.lightClickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}.padding(vertical = 0.55f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(if (caps) label.uppercase() else label, LightTextVariant.Copy, Modifier.padding(end = 1f.gridUnitsAsDp()), lighten = !enabled,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) LightText(value, LightTextVariant.Copy, Modifier.weight(1f), align = TextAlign.End, lighten = true,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!detail.isNullOrEmpty()) LightText(detail, LightTextVariant.Fine, lighten = true)
    }
}

/** A few sentences of explanation. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) =
    LightText(text, LightTextVariant.Paragraph, modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()))

/** A short line that heads a few rows. */
@Composable
fun Heading(text: String) = LightText(text, LightTextVariant.Subheading,
    Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()).semantics { heading() })

/**
 * A value to tap and change, drawn as Light draws a field: the value over an underline, a small label under it
 * ([placeholder] stands in while there's no value). Typing happens on a screen of its own, so [onClick] opens it.
 */
@Composable
fun TitleField(value: String, placeholder: String, label: String, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 1.5f.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        LightText(value.ifBlank { placeholder }, LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        Box(Modifier.fillMaxWidth().height(3f.designVerticalPxToDp()).background(colors.content))
        LightText(label, LightTextVariant.Detail, Modifier.padding(top = 0.4f.gridUnitsAsDp()))
    }
}
