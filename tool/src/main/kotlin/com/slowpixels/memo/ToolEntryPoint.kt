package com.slowpixels.memo

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec

/**
 * What Light's Tool Manager shows of Memo in a browser on a computer: the memos folder, to copy memos off the phone or
 * put sound files on it. It can be included in the user's own backups.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest() = ClientToolManifest(
        title = "Memo",
        roots = listOf(
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Memos",
                    path = Hub.MEMOS,
                    headerText = "Your memos, as AAC sound files named by the date and time they were made. " +
                        "Sound files you add here join the list in Memo.",
                ),
                canBeBackedUp = true,
            ),
        ),
    )

    /** Files were added or taken away from a computer. */
    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        Hub.refresh()
    }
}
