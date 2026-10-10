package com.gpxtoposter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpxtoposter.app.MainViewModel
import com.gpxtoposter.app.RouteSource
import com.gpxtoposter.app.Tab
import com.gpxtoposter.app.data.Job
import com.gpxtoposter.app.data.PosterFiles
import com.gpxtoposter.app.data.PosterOptions
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.util.Locale

private data class SizePreset(val label: String, val width: Double, val height: Double)

private val SIZE_PRESETS = listOf(
    SizePreset("12 × 16 in (default)", 12.0, 16.0),
    SizePreset("15 × 20 in", 15.0, 20.0), // Server caps each side at 20 in
    SizePreset("11 × 14 in", 11.0, 14.0),
    SizePreset("A4 (8.3 × 11.7 in)", 8.3, 11.7),
    SizePreset("A3 (11.7 × 16.5 in)", 11.7, 16.5),
    SizePreset("Square 12 × 12 in", 12.0, 12.0),
    SizePreset("Phone wallpaper (6 × 13 in)", 6.0, 13.0),
)

private val FORMATS = listOf("png", "pdf", "svg")

@Composable
fun CreateScreen(vm: MainViewModel, onPickFile: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("New poster", style = MaterialTheme.typography.headlineSmall)
        SourceCard(
            source = vm.source,
            onPickFile = onPickFile,
            onStrava = { vm.tab = Tab.Strava },
            onClear = vm::clearSource,
        )
        OptionsCard(vm.options, vm.themes, vm::updateOptions)
        Button(
            onClick = vm::generate,
            enabled = vm.source != null && !vm.submitting && vm.currentJob?.isActive != true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vm.submitting) "Uploading…" else "Generate poster")
        }
        vm.currentJob?.let { job ->
            JobCard(
                job = job,
                file = vm.currentFile,
                onShare = { PosterFiles.share(context, it) },
                onOpen = { PosterFiles.open(context, it) },
                onSave = vm::saveToDevice,
                onDismiss = vm::dismissJob,
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: RouteSource?,
    onPickFile: () -> Unit,
    onStrava: () -> Unit,
    onClear: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Route", style = MaterialTheme.typography.titleMedium)
            when (source) {
                null -> Text(
                    "Choose a GPX, FIT or TCX file (zip and gz work too), share one to this app, " +
                        "or pick a Strava activity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is RouteSource.Track ->
                    SourceSummary(source.file.name, formatBytes(source.file.bytes.size), onClear)
                is RouteSource.Strava ->
                    SourceSummary(source.activity.name, "Strava · ${activitySummary(source.activity)}", onClear)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFile) { Text("Choose file") }
                OutlinedButton(onClick = onStrava) { Text("From Strava") }
            }
        }
    }
}

@Composable
private fun SourceSummary(title: String, subtitle: String, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Clear route")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(
    options: PosterOptions,
    themes: List<String>,
    onChange: (PosterOptions) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Style", style = MaterialTheme.typography.titleMedium)
            Dropdown(
                label = "Theme",
                selected = humanize(options.theme),
                items = themes.ifEmpty { listOf(options.theme) },
                itemLabel = ::humanize,
                onSelect = { onChange(options.copy(theme = it)) },
            )
            Dropdown(
                label = "Size",
                selected = SIZE_PRESETS.firstOrNull { it.width == options.width && it.height == options.height }
                    ?.label
                    ?: String.format(Locale.US, "%.1f × %.1f in", options.width, options.height),
                items = SIZE_PRESETS,
                itemLabel = { it.label },
                onSelect = { onChange(options.copy(width = it.width, height = it.height)) },
            )
            OutlinedTextField(
                value = options.city.orEmpty(),
                onValueChange = { onChange(options.copy(city = it.ifEmpty { null })) },
                label = { Text("Title (city)") },
                placeholder = { Text("Auto-detect") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = options.country.orEmpty(),
                onValueChange = { onChange(options.copy(country = it.ifEmpty { null })) },
                label = { Text("Subtitle (country)") },
                placeholder = { Text("Auto-detect") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FORMATS.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = options.format == format,
                        onClick = { onChange(options.copy(format = format)) },
                        shape = SegmentedButtonDefaults.itemShape(index, FORMATS.size),
                    ) {
                        Text(format.uppercase())
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show start coordinates", Modifier.weight(1f))
                Switch(
                    checked = options.showCoordinates,
                    onCheckedChange = { onChange(options.copy(showCoordinates = it)) },
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: Job,
    file: File?,
    onShare: (File) -> Unit,
    onOpen: (File) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        job.isActive -> "Creating poster…"
                        job.status == "failed" -> "Poster failed"
                        else -> "Poster ready"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            when {
                job.isActive -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ElapsedText(job)
                }
                job.status == "failed" -> Text(job.error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                file == null -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    if (file.extension.equals("png", ignoreCase = true)) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Poster preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        )
                    }
                    Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onShare(file) }) { Text("Share") }
                        OutlinedButton(onClick = onSave) { Text("Save") }
                        OutlinedButton(onClick = { onOpen(file) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElapsedText(job: Job) {
    val createdAt = remember(job.id) { runCatching { Instant.parse(job.createdAt).toEpochMilli() }.getOrNull() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job.id) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val state = if (job.status == "queued") "Waiting in queue" else "Rendering"
    val elapsed = createdAt?.let {
        val seconds = maxOf(0L, (now - it) / 1000)
        String.format(Locale.US, " · %d:%02d", seconds / 60, seconds % 60)
    }.orEmpty()
    Text(
        "$state$elapsed\nUsually takes 1–3 minutes; you can leave the app meanwhile.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
