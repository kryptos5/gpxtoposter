package com.gpxtoposter.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.gpxtoposter.app.MainViewModel
import com.gpxtoposter.app.Tab

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Create -> Icons.Filled.Add
        Tab.Strava -> Icons.Filled.Place
        Tab.Posters -> Icons.AutoMirrored.Filled.List
        Tab.Settings -> Icons.Filled.Settings
    }

@Composable
fun App(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    // FIT/TCX files have no reliable MIME type, so accept anything and let the server decide.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadTrack)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = vm.tab == tab,
                        onClick = { vm.tab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.name) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (vm.tab) {
                Tab.Create -> CreateScreen(vm, onPickFile = { picker.launch(arrayOf("*/*")) })
                Tab.Strava -> StravaScreen(vm)
                Tab.Posters -> PostersScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}
