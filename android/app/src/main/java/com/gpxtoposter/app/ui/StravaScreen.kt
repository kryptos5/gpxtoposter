package com.gpxtoposter.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.gpxtoposter.app.MainViewModel

@Composable
fun StravaScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        if (vm.stravaActivities.isEmpty() && vm.stravaError == null) vm.loadStrava(refresh = true)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Strava activities") {
            IconButton(onClick = { vm.loadStrava(refresh = true) }, enabled = !vm.stravaLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        vm.stravaError?.let {
            ErrorCard(
                it,
                hint = "Strava import needs a Strava subscription and a one-time " +
                    "`python strava_source.py auth` on the server. You can always export a GPX or " +
                    "FIT file instead and share it to this app.",
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.stravaActivities, key = { it.id }) { activity ->
                ListItem(
                    headlineContent = { Text(activity.name) },
                    supportingContent = {
                        val summary = activitySummary(activity)
                        Text(if (activity.hasGps) summary else "$summary · no GPS track")
                    },
                    modifier = Modifier
                        .clickable(enabled = activity.hasGps) { vm.selectStrava(activity) }
                        .alpha(if (activity.hasGps) 1f else 0.5f),
                )
                HorizontalDivider()
            }
            when {
                vm.stravaLoading -> item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                vm.stravaHasMore && vm.stravaActivities.isNotEmpty() -> item {
                    TextButton(onClick = { vm.loadStrava() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more")
                    }
                }
                vm.stravaActivities.isEmpty() && vm.stravaError == null -> item {
                    Text(
                        "No activities found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
