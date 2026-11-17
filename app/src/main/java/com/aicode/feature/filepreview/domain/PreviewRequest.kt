package com.aicode.feature.filepreview.domain

import androidx.compose.runtime.Immutable

@Immutable
data class PreviewRequest(
    val path: String,
    val title: String = "",
    val mimeType: String = "",
    val cachedPath: String = "",
    val recordedLocalPath: String = "",
)

internal object PreviewLimits {
    const val TEXT_BYTES = 2 * 1024 * 1024
    const val FILE_BYTES = 100L * 1024 * 1024
    const val RESOURCE_BYTES = 8 * 1024 * 1024
    const val CSV_ROWS = 200
    const val CSV_COLUMNS = 50
    const val ARCHIVE_ENTRIES = 2_000
    const val PDF_CACHE_BYTES = 24L * 1024 * 1024
}
