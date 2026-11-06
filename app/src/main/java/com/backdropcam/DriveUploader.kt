package com.backdropcam

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a video to the signed-in user's Google Drive (resumable upload, streamed from
 * the gallery URI so large 4K files never sit in memory) and makes it viewable by anyone
 * with the link. Must be called from a background thread.
 */
class DriveUploader(private val resolver: ContentResolver, private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** @return the shareable web link, e.g. https://drive.google.com/file/d/ID/view */
    fun uploadAndShare(uri: Uri, onProgress: (Int) -> Unit): String {
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size <= 0) throw IOException("Cannot read the video's size")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "BackdropCam.mp4"

        // 1. Start a resumable session
        val meta = JSONObject().put("name", name).put("mimeType", "video/mp4").toString()
        val start = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", size.toString())
            .post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val sessionUrl = http.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Drive start failed: ${r.code} ${r.body?.string()}")
            r.header("Location") ?: throw IOException("Drive gave no upload URL")
        }

        // 2. Stream the file
        val body = object : RequestBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)!!.source().use { src ->
                    var sent = 0L
                    var lastPct = -1
                    while (true) {
                        val n = src.read(sink.buffer, 256 * 1024)
                        if (n < 0) break
                        sink.flush()
                        sent += n
                        val pct = (sent * 100 / size).toInt()
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        }
        val put = Request.Builder().url(sessionUrl).put(body).build()
        val fileJson = http.newCall(put).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Drive upload failed: ${r.code} ${r.body?.string()}")
            JSONObject(r.body!!.string())
        }
        val id = fileJson.getString("id")

        // 3. Anyone with the link can view
        val perm = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id/permissions")
            .header("Authorization", "Bearer $accessToken")
            .post("""{"role":"reader","type":"anyone"}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(perm).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Could not create share link: ${r.code} ${r.body?.string()}")
        }

        return fileJson.optString("webViewLink", "https://drive.google.com/file/d/$id/view")
    }
}
