package com.gpxtoposter.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Poster options; field names match server.py's PosterOptions. */
@Serializable
data class PosterOptions(
    val theme: String = "terracotta",
    val city: String? = null,
    val country: String? = null,
    val width: Double = 12.0,
    val height: Double = 16.0,
    val format: String = "png",
    @SerialName("show_coordinates") val showCoordinates: Boolean = true,
)

@Serializable
data class Job(
    val id: String,
    val status: String,
    val source: String,
    val format: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val error: String? = null,
    @SerialName("file_name") val fileName: String? = null,
) {
    val isActive: Boolean get() = status == "queued" || status == "running"
}

@Serializable
data class StravaActivity(
    val id: Long,
    val name: String,
    @SerialName("sport_type") val sportType: String? = null,
    @SerialName("start_date_local") val startDateLocal: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("moving_time_s") val movingTimeS: Int? = null,
    @SerialName("has_gps") val hasGps: Boolean = false,
)

class ApiException(message: String, val code: Int = 0) : IOException(message)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Client for the poster server (server.py). */
class PosterApi(baseUrl: String, private val apiKey: String) {
    private val base = baseUrl.trim().trimEnd('/')

    suspend fun health() {
        get("/health")
    }

    suspend fun themes(): List<String> = ApiJson.decodeFromString(get("/themes"))

    suspend fun stravaActivities(page: Int, perPage: Int): List<StravaActivity> =
        ApiJson.decodeFromString(get("/strava/activities?page=$page&per_page=$perPage"))

    suspend fun jobs(): List<Job> = ApiJson.decodeFromString(get("/posters"))

    suspend fun job(id: String): Job = ApiJson.decodeFromString(get("/posters/$id"))

    suspend fun submitStrava(activityId: Long, options: PosterOptions): Job {
        val body = buildJsonObject {
            ApiJson.encodeToJsonElement(options).jsonObject.forEach { (key, value) -> put(key, value) }
            put("activity_id", activityId)
        }
        val request = request("/posters/strava").post(body.toString().toRequestBody(JSON)).build()
        return execute(request) { ApiJson.decodeFromString(it.body!!.string()) }
    }

    suspend fun submitTrack(fileName: String, bytes: ByteArray, options: PosterOptions): Job {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(OCTET_STREAM))
            .addFormDataPart("options", ApiJson.encodeToString(options))
            .build()
        val request = request("/posters/gpx").post(body).build()
        return execute(request) { ApiJson.decodeFromString(it.body!!.string()) }
    }

    /** Download a finished poster to [dest] (written via a temp file so partial downloads never linger). */
    suspend fun download(job: Job, dest: File) {
        execute(request("/posters/${job.id}/file").get().build()) { response ->
            val tmp = File(dest.path + ".part")
            response.body!!.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save ${dest.name}")
        }
    }

    private suspend fun get(path: String): String =
        execute(request(path).get().build()) { it.body!!.string() }

    private fun request(path: String): Request.Builder {
        val url = "$base$path".toHttpUrlOrNull()
            ?: throw ApiException("Invalid server URL: $base")
        return Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) header("X-API-Key", apiKey)
        }
    }

    private suspend fun <T> execute(request: Request, handle: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw ApiException(errorMessage(response.code, body), response.code)
                }
                handle(response)
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        // FastAPI errors look like {"detail": "..."} or {"detail": [{"msg": "..."}, ...]}
        val detail = runCatching {
            when (val d = ApiJson.parseToJsonElement(body).jsonObject["detail"]) {
                is JsonPrimitive -> d.contentOrNull
                is JsonArray -> d.joinToString("; ") { e ->
                    (e as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull ?: e.toString()
                }
                else -> null
            }
        }.getOrNull()
        return when {
            code == 401 -> "Invalid or missing API key"
            detail != null -> detail
            else -> "Server error $code"
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
