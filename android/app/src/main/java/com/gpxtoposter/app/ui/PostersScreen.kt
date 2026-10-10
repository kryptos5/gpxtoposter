package com.gpxtoposter.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpxtoposter.app.MainViewModel

@Composable
fun PostersScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadJobs() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Posters") {
            IconButton(onClick = vm::loadJobs, enabled = !vm.jobsLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        vm.jobsError?.let { ErrorCard(it) }
        if (vm.jobsLoading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            if (!vm.jobsLoading && vm.jobs.isEmpty() && vm.jobsError == null) {
                item {
                    Text(
                        "No posters since the server last started. Earlier posters are still in " +
                            "the server's posters/ folder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(vm.jobs, key = { it.id }) { job ->
                ListItem(
                    headlineContent = {
                        Text(job.fileName ?: job.source, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text("${humanize(job.status)} · ${job.createdAt.take(16).replace('T', ' ')} UTC")
                    },
                    modifier = Modifier.clickable { vm.showJob(job) },
                )
                HorizontalDivider()
            }
        }
    }
}
