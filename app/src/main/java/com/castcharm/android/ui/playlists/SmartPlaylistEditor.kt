@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.castcharm.android.ui.playlists

// The smart-playlist editor, shared by "New smart playlist" and "Edit rules".
// Mirrors the web editor: the base facts (played state, order, cap), then any
// conditions the user layers in, with a live "Matches N episodes" line.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.castcharm.android.data.api.models.PLAYLIST_DESC_MAX
import com.castcharm.android.data.api.models.PLAYLIST_NAME_MAX
import com.castcharm.android.data.api.models.SmartDurationRule
import com.castcharm.android.data.api.models.SmartFeedsRule
import com.castcharm.android.data.api.models.SmartPublishedRule
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.db.entities.FeedEntity
import kotlinx.coroutines.delay

/** What a set of rules matches right now: count, total seconds, first titles. */
data class PreviewResult(val count: Int, val totalSeconds: Int, val sample: List<String> = emptyList())

private fun isRealDate(s: String): Boolean =
    runCatching { java.time.LocalDate.parse(s) }.isSuccess

fun formatHours(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600 + 30) / 60
    return if (h > 0) "$h h${if (m > 0) " $m m" else ""}" else "$m m"
}

@Composable
fun SmartPlaylistEditor(
    title: String,
    initialName: String,
    initialDescription: String,
    initialRules: SmartRules?,
    nameLocked: Boolean,
    feeds: List<FeedEntity>,
    preview: suspend (SmartRules) -> PreviewResult,
    onSave: (name: String, description: String?, rules: SmartRules) -> Unit,
    onDismiss: () -> Unit,
) {
    val r = initialRules ?: SmartRules()
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var played by remember { mutableStateOf(r.played) }
    var sort by remember { mutableStateOf(r.sort) }
    var limitText by remember { mutableStateOf(r.limit?.toString() ?: "") }

    var feedsOn by remember { mutableStateOf(r.feeds?.ids?.isNotEmpty() == true) }
    var feedsMode by remember { mutableStateOf(r.feeds?.mode ?: "include") }
    var feedIds by remember { mutableStateOf(r.feeds?.ids?.toSet() ?: emptySet()) }

    var publishedOn by remember { mutableStateOf(r.published != null) }
    var publishedKind by remember { mutableStateOf(when { r.published?.after != null -> "after"; r.published?.before != null -> "before"; else -> "within_days" }) }
    var publishedValue by remember { mutableStateOf(r.published?.within_days?.toString() ?: r.published?.after ?: r.published?.before ?: "14") }

    var durationOn by remember { mutableStateOf(r.duration != null) }
    var minText by remember { mutableStateOf(r.duration?.min_minutes?.toString() ?: "") }
    var maxText by remember { mutableStateOf(r.duration?.max_minutes?.toString() ?: "") }

    var titleOn by remember { mutableStateOf(!r.title_contains.isNullOrBlank()) }
    var titleText by remember { mutableStateOf(r.title_contains ?: "") }

    fun collect(): SmartRules = SmartRules(
        feeds = if (feedsOn && feedIds.isNotEmpty()) SmartFeedsRule(feedsMode, feedIds.toList()) else null,
        played = played,
        published = if (!publishedOn) null else when (publishedKind) {
            // Same rules as the web editor: 0 or blank is "no condition", not "1".
            "within_days" -> publishedValue.toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(3650)?.let { SmartPublishedRule(within_days = it) }
            "after" -> publishedValue.takeIf(::isRealDate)?.let { SmartPublishedRule(after = it) }
            else -> publishedValue.takeIf(::isRealDate)?.let { SmartPublishedRule(before = it) }
        },
        duration = if (!durationOn) null else {
            val mn = minText.toIntOrNull()?.coerceIn(0, 100_000)
            val mx = maxText.toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(100_000)
            if (mn == null && mx == null) null else SmartDurationRule(mn, mx)
        },
        title_contains = if (titleOn) titleText.trim().takeIf { it.isNotBlank() } else null,
        sort = sort,
        limit = limitText.toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(1000),
    )

    val rules = collect()
    var previewText by remember { mutableStateOf("Checking what this matches…") }
    LaunchedEffect(rules) {
        delay(300)
        val result = runCatching { preview(rules) }.getOrNull()
        previewText = when {
            result == null -> "Couldn't check these rules right now"
            result.count == 0 -> "Matches no episodes right now"
            else -> {
                val n = result.count
                val sample = result.sample.take(2).joinToString(", ")
                "Matches $n episode${if (n == 1) "" else "s"}${if (result.totalSeconds > 0) " · ${formatHours(result.totalSeconds)}" else ""}" +
                    (if (sample.isNotBlank()) " — starting with $sample${if (n > 2) ", …" else ""}" else "")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(
                modifier = Modifier.padding(20.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(PLAYLIST_NAME_MAX) },
                    label = { Text("Name") }, singleLine = true, enabled = !nameLocked,
                    supportingText = if (nameLocked) ({ Text("Built-in playlists keep their name") }) else null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it.take(PLAYLIST_DESC_MAX) },
                    label = { Text("Description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                SectionLabel("Episodes that are")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SmartRules.PLAYED_OPTIONS.forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = played == v, onClick = { played = v },
                            shape = SegmentedButtonDefaults.itemShape(i, SmartRules.PLAYED_OPTIONS.size)
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Text("…and downloaded on the server. “Unplayed” includes episodes you've started but not finished.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                SectionLabel("Conditions")
                ConditionRow("Podcast", feedsOn, { feedsOn = it }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("include" to "is any of", "exclude" to "is none of").forEachIndexed { i, (v, l) ->
                            SegmentedButton(selected = feedsMode == v, onClick = { feedsMode = v },
                                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        feeds.forEach { f ->
                            FilterChip(selected = f.id in feedIds,
                                onClick = { feedIds = if (f.id in feedIds) feedIds - f.id else feedIds + f.id },
                                label = { Text(f.title, maxLines = 1) })
                        }
                    }
                }
                ConditionRow("Published", publishedOn, { publishedOn = it }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("within_days" to "in the last", "after" to "after", "before" to "before").forEachIndexed { i, (v, l) ->
                            SegmentedButton(selected = publishedKind == v,
                                onClick = { publishedKind = v; publishedValue = if (v == "within_days") "14" else "" },
                                shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                        }
                    }
                    OutlinedTextField(
                        value = publishedValue, onValueChange = { publishedValue = it },
                        label = { Text(if (publishedKind == "within_days") "Days" else "Date (YYYY-MM-DD)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (publishedKind == "within_days") KeyboardType.Number else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ConditionRow("Duration", durationOn, { durationOn = it }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = minText, onValueChange = { minText = it.filter(Char::isDigit) }, label = { Text("At least (min)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = maxText, onValueChange = { maxText = it.filter(Char::isDigit) }, label = { Text("At most (min)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                }
                ConditionRow("Title contains", titleOn, { titleOn = it }) {
                    OutlinedTextField(value = titleText, onValueChange = { titleText = it.take(200) }, label = { Text("Words in the title") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                SectionLabel("Order")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmartRules.SORT_OPTIONS.forEach { (v, l) ->
                        FilterChip(selected = sort == v, onClick = { sort = v }, label = { Text(l) })
                    }
                }
                OutlinedTextField(
                    value = limitText, onValueChange = { limitText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Keep only the first (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(220.dp)
                )

                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(previewText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onSave(name.trim(), description.trim().takeIf { it.isNotBlank() }, collect()) },
                        enabled = nameLocked || name.isNotBlank()
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConditionRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onToggle)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (enabled) Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
