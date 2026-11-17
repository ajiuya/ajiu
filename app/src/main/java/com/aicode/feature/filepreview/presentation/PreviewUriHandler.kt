package com.aicode.feature.filepreview.presentation

import androidx.compose.ui.platform.UriHandler
import com.aicode.asChatFilePath
import com.aicode.splitPathAndLine
import com.aicode.feature.filepreview.domain.PreviewKind
import com.aicode.feature.filepreview.domain.PreviewTypes

internal fun attachmentUsesPlayer(path: String, mimeType: String): Boolean {
    if (path.substringBefore('?').substringBefore('#').substringAfterLast('.').equals("ts", true)) {
        return mimeType.substringBefore(';').trim().equals("video/mp2t", true)
    }
    return PreviewTypes.kind(path, mimeType) == PreviewKind.MEDIA
}

internal class PreviewUriHandler(
    private val delegate: UriHandler,
    private val onPreview: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        val file = asChatFilePath(uri)
        if (file == null) {
            delegate.openUri(uri)
            return
        }
        val (path, line) = splitPathAndLine(file)
        if (line > 0) delegate.openUri(uri) else onPreview(path)
    }
}
