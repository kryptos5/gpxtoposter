package com.gpxtoposter.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpxtoposter.app.data.ApiException
import com.gpxtoposter.app.data.Job
import com.gpxtoposter.app.data.PosterApi
import com.gpxtoposter.app.data.PosterFiles
import com.gpxtoposter.app.data.PosterOptions
import com.gpxtoposter.app.data.SettingsStore
import com.gpxtoposter.app.data.StravaActivity
import com.gpxtoposter.app.data.TrackFile
import com.gpxtoposter.app.data.readTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.Job as CoroutineJob

enum class Tab { Create, Strava, Posters, Settings }

sealed interface RouteSource {
    class Track(val file: TrackFile) : RouteSource
    class Strava(val activity: StravaActivity) : RouteSource
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)

    var tab by mutableStateOf(if (settings.serverUrl.isBlank()) Tab.Settings else Tab.Create)
    var message by mutableStateOf<String?>(null)

    var serverUrl by mutableStateOf(settings.serverUrl); private set
    var apiKey by mutableStateOf(settings.apiKey); private set
    var connectionStatus by mutableStateOf<String?>(null); private set
    var themes by mutableStateOf<List<String>>(emptyList()); private set
    var options by mutableStateOf(settings.options); private set

    var source by mutableStateOf<RouteSource?>(null); private set
    var submitting by mutableStateOf(false); private set
    var currentJob by mutableStateOf<Job?>(null); private set
    var currentFile by mutableStateOf<File?>(null); private set

    var stravaActivities by mutableStateOf<List<StravaActivity>>(emptyList()); private set
    var stravaLoading by mutableStateOf(false); private set
    var stravaError by mutableStateOf<String?>(null); private set
    var stravaHasMore by mutableStateOf(true); private set
    private var stravaPage = 0

    var jobs by mutableStateOf<List<Job>>(emptyList()); private set
    var jobsLoading by mutableStateOf(false); private set
    var jobsError by mutableStateOf<String?>(null); private set

    private var pollJob: CoroutineJob? = null

    val isConfigured: Boolean get() = serverUrl.isNotBlank()

    private fun api() = PosterApi(serverUrl, apiKey)

    init {
        if (isConfigured) {
            loadThemes()
            settings.lastJobId?.let(::resumeJob)
        }
    }

    // --- Settings -----------------------------------------------------------

    fun saveSettings(url: String, key: String) {
        serverUrl = url.trim().trimEnd('/')
        apiKey = key.trim()
        settings.serverUrl = serverUrl
        settings.apiKey = apiKey
        stravaActivities = emptyList()
        stravaPage = 0
        stravaHasMore = true
        stravaError = null
        testConnection()
    }

    private fun testConnection() {
        viewModelScope.launch {
            connectionStatus = "Connecting…"
            val api = api()
            connectionStatus = safely {
                api.health()
                api.themes() // Requires the API key, so this also checks it
            }.fold(
                onSuccess = {
                    themes = it
                    "Connected · ${it.size} themes available"
                },
                onFailure = { "Connection failed: ${it.message}" },
            )
        }
    }

    private fun loadThemes() {
        viewModelScope.launch {
            safely { api().themes() }.onSuccess { themes = it }
        }
    }

    // --- Poster creation ----------------------------------------------------

    fun updateOptions(newOptions: PosterOptions) {
        options = newOptions
        settings.options = newOptions
    }

    fun loadTrack(uri: Uri) {
        viewModelScope.launch {
            safely { withContext(Dispatchers.IO) { readTrack(getApplication(), uri) } }
                .onSuccess {
                    source = RouteSource.Track(it)
                    tab = Tab.Create
                }
                .onFailure { message = "Couldn't read file: ${it.message}" }
        }
    }

    fun selectStrava(activity: StravaActivity) {
        source = RouteSource.Strava(activity)
        tab = Tab.Create
    }

    fun clearSource() {
        source = null
    }

    fun generate() {
        val src = source ?: return
        if (!isConfigured) {
            message = "Set the server URL first"
            tab = Tab.Settings
            return
        }
        submitting = true
        viewModelScope.launch {
            val api = api()
            val opts = options
            safely {
                when (src) {
                    is RouteSource.Track -> api.submitTrack(src.file.name, src.file.bytes, opts)
                    is RouteSource.Strava -> api.submitStrava(src.activity.id, opts)
                }
            }
                .onSuccess(::follow)
                .onFailure { message = "Couldn't start poster: ${it.message}" }
            submitting = false
        }
    }

    /** Show a poster from the history: follow it if still rendering, download it if done. */
    fun showJob(job: Job) {
        tab = Tab.Create
        follow(job)
    }

    fun dismissJob() {
        pollJob?.cancel()
        currentJob = null
        currentFile = null
        settings.lastJobId = null
    }

    fun saveToDevice() {
        val file = currentFile ?: return
        viewModelScope.launch {
            safely { PosterFiles.saveToDevice(getApplication(), file) }
                .onSuccess { message = "Saved to $it" }
                .onFailure { message = "Save failed: ${it.message}" }
        }
    }

    private fun resumeJob(id: String) {
        viewModelScope.launch {
            safely { api().job(id) }
                .onSuccess(::follow)
                .onFailure { settings.lastJobId = null }
        }
    }

    /** Poll [job] until it finishes, then download the poster. */
    private fun follow(job: Job) {
        currentJob = job
        currentFile = null
        settings.lastJobId = job.id
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var latest = job
            while (latest.isActive) {
                delay(POLL_INTERVAL_MS)
                val result = safely { api().job(latest.id) }
                val error = result.exceptionOrNull()
                if (error is ApiException && error.code == 404) {
                    message = "The server no longer knows this poster (was it restarted?)"
                    currentJob = null
                    settings.lastJobId = null
                    return@launch
                }
                latest = result.getOrNull() ?: continue // Transient network error: keep polling
                currentJob = latest
            }
            if (latest.status == "done") fetchFile(latest)
        }
    }

    private suspend fun fetchFile(job: Job) {
        val dest = File(PosterFiles.dir(getApplication()), job.fileName ?: "${job.id}.${job.format}")
        if (!dest.exists()) {
            safely { api().download(job, dest) }.onFailure {
                message = "Download failed: ${it.message}"
                return
            }
        }
        currentFile = dest
    }

    // --- Strava -------------------------------------------------------------

    fun loadStrava(refresh: Boolean = false) {
        if (stravaLoading) return
        if (!isConfigured) {
            stravaError = "Set the server URL in Settings first"
            return
        }
        if (refresh) {
            stravaPage = 0
            stravaHasMore = true
        }
        stravaLoading = true
        stravaError = null
        viewModelScope.launch {
            val page = stravaPage + 1
            safely { api().stravaActivities(page, STRAVA_PAGE_SIZE) }
                .onSuccess { list ->
                    stravaActivities = if (page == 1) list else stravaActivities + list
                    stravaPage = page
                    stravaHasMore = list.size >= STRAVA_PAGE_SIZE
                }
                .onFailure { stravaError = it.message }
            stravaLoading = false
        }
    }

    // --- Poster history -----------------------------------------------------

    fun loadJobs() {
        if (jobsLoading) return
        if (!isConfigured) {
            jobsError = "Set the server URL in Settings first"
            return
        }
        jobsLoading = true
        jobsError = null
        viewModelScope.launch {
            safely { api().jobs() }
                .onSuccess { jobs = it }
                .onFailure { jobsError = it.message }
            jobsLoading = false
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val STRAVA_PAGE_SIZE = 30
    }
}
