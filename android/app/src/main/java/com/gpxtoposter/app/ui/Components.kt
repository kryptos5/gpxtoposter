package com.gpxtoposter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpxtoposter.app.data.StravaActivity
import java.util.Locale

@Composable
fun ScreenHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ErrorCard(message: String, hint: String? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    selected: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** "midnight_blue" -> "Midnight blue", "TrailRun" -> "Trail run". */
fun humanize(value: String): String =
    value.replace('_', ' ')
        .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        .lowercase()
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

fun formatBytes(bytes: Int): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else "${maxOf(1, bytes / 1024)} KB"

fun activitySummary(activity: StravaActivity): String = listOfNotNull(
    activity.startDateLocal?.take(10),
    activity.distanceM?.let { String.format(Locale.US, "%.1f km", it / 1000) },
    activity.sportType?.let(::humanize),
).joinToString(" · ")
