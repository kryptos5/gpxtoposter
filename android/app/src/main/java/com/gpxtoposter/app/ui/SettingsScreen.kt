package com.gpxtoposter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gpxtoposter.app.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf(vm.serverUrl) }
    var key by rememberSaveable { mutableStateOf(vm.apiKey) }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://my-pc:8000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.saveSettings(url, key) },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save & test connection")
        }
        vm.connectionStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Start the server on your PC with\n" +
                "uvicorn server:app --host 0.0.0.0 --port 8000\n" +
                "and POSTER_API_KEY set to the key above. With Tailscale on both devices, use the " +
                "PC's Tailscale name or IP, e.g. http://my-pc:8000.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
