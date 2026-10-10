package com.gpxtoposter.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Persists server connection settings, the last-used poster options and the job being followed. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var options: PosterOptions
        get() = prefs.getString(KEY_OPTIONS, null)
            ?.let { runCatching { ApiJson.decodeFromString<PosterOptions>(it) }.getOrNull() }
            ?: PosterOptions()
        set(value) = prefs.edit { putString(KEY_OPTIONS, ApiJson.encodeToString(value)) }

    var lastJobId: String?
        get() = prefs.getString(KEY_LAST_JOB, null)
        set(value) = prefs.edit { putString(KEY_LAST_JOB, value) }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_OPTIONS = "poster_options"
        const val KEY_LAST_JOB = "last_job_id"
    }
}
