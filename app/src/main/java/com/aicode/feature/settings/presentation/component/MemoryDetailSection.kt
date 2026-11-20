package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.presentation.component.MarkdownContent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Trash2

@Composable
internal fun MemoryDetailSection(
    document: MemoryDocument,
    projectName: String? = null,
    busy: Boolean = false,
    onDelete: (MemoryEntry) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val semantic = MaterialTheme.semanticColors
    val cardShape = RoundedCornerShape(Radius.lg)
    val actionShape = RoundedCornerShape(Radius.mdLarge)
    val scopeLabel = when (document.entry.scope) {
        MemoryScope.GLOBAL -> stringResource(memoryScopeLabelRes(MemoryScope.GLOBAL))
        MemoryScope.PROJECT -> if (projectName.isNullOrBlank()) {
            stringResource(R.string.memory_a_current_project)
        } else {
            stringResource(R.string.memory_management_project_named, projectName)
        }
    }
    var moreExpanded by rememberSaveable(document.entry.id, document.entry.version) { mutableStateOf(false) }
    DisposableEffect(document.entry.id, document.entry.version) {
        onDispose { moreExpanded = false }
    }
    LaunchedEffect(busy) {
        if (busy) moreExpanded = false
    }

    Column(Modifier.fillMaxSize().background(semantic.pageBackground)) {
        key(document.entry.id, document.entry.version) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.xs, bottom = Spacing.xl)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("memory-detail-info"),
                    shape = RoundedCornerShape(Radius.mdLarge),
                    color = semantic.cardSurface,
                    border = BorderStroke(1.dp, semantic.subtleBorder)
                ) {
                    Column(
                        Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.memory_management_scope),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.subtleText
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = Spacing.md)
                                    .testTag("memory-detail-scope"),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (document.entry.scope == MemoryScope.GLOBAL) {
                                        FeatherIcons.Globe
                                    } else FeatherIcons.Folder,
                                    contentDescription = null,
                                    tint = semantic.subtleText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    scopeLabel,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.memory_management_file),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.subtleText
                            )
                            Text(
                                document.entry.fileName,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = Spacing.md)
                                    .testTag("memory-detail-file"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                SelectionContainer {
                    Column {
                        Text(
                            stringResource(R.string.memory_management_description),
                            modifier = Modifier.padding(
                                start = Spacing.xs, top = Spacing.lg + Spacing.xs, bottom = Spacing.sm
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.subtleText
                        )
                        SettingsGroup(
                            modifier = Modifier
                                .testTag("memory-detail-description")
                                .border(BorderStroke(1.dp, semantic.subtleBorder), cardShape)
                        ) {
                            Text(
                                document.entry.description.ifBlank {
                                    stringResource(R.string.memory_management_no_description)
                                },
                                modifier = Modifier.padding(Spacing.lg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(R.string.memory_management_body),
                            modifier = Modifier.padding(
                                start = Spacing.xs, top = Spacing.lg + Spacing.xs, bottom = Spacing.sm
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.subtleText
                        )
                        SettingsGroup(
                            modifier = Modifier
                                .testTag("memory-detail-body")
                                .border(BorderStroke(1.dp, semantic.subtleBorder), cardShape)
                        ) {
                            MarkdownContent(
                                text = document.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(Spacing.lg + Spacing.xs)
                            )
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag("memory-detail-actions")) {
            HorizontalDivider(thickness = 1.dp, color = semantic.subtleBorder)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (!busy) clipboard.setText(AnnotatedString(document.content)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memory-copy-body"),
                    shape = actionShape,
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Icon(FeatherIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.memory_management_copy_body), textAlign = TextAlign.Center)
                }
                Box {
                    OutlinedButton(
                        onClick = { if (!busy) moreExpanded = true },
                        enabled = !busy,
                        modifier = Modifier
                            .widthIn(min = 96.dp)
                            .heightIn(min = 48.dp)
                            .testTag("memory-detail-more"),
                        shape = actionShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = semantic.cardSurface,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(1.dp, semantic.subtleBorder),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        Icon(FeatherIcons.MoreHorizontal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.memory_a_more))
                    }
                    DropdownMenu(
                        expanded = moreExpanded && !busy,
                        onDismissRequest = { moreExpanded = false },
                        modifier = Modifier.widthIn(min = 144.dp),
                        offset = DpOffset(0.dp, Spacing.sm),
                        shape = actionShape,
                        containerColor = semantic.cardSurface,
                        tonalElevation = 0.dp,
                        shadowElevation = Spacing.xs,
                        border = BorderStroke(1.dp, semantic.subtleBorder)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.memory_a_delete)) },
                            leadingIcon = {
                                Icon(FeatherIcons.Trash2, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                if (!busy) {
                                    moreExpanded = false
                                    onDelete(document.entry)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("memory-detail-delete"),
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        }
    }
}
