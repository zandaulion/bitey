package com.zandaulion.bitey

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID

val CAPTURE_ID = Regex("[A-Za-z0-9_-]{1,80}\\.(jpg|png)")

/**
 * A short-lived holding area for a photograph that native code has just taken
 * or picked, before the packaged interface has read it.
 *
 * The page cannot open a content:// URI, and pushing several megabytes of
 * base64 through evaluateJavascript is worse still. The bytes therefore stay
 * in app-private cache and the page is handed a plate.local path it can fetch,
 * which [PlateAssetClient] serves. The original file is copied rather than
 * re-encoded, so the EXIF capture date the diary offers survives.
 *
 * Nothing here is a diary photograph. A capture the page never collected — a
 * review sheet abandoned, the app killed mid-flow — is deleted when the next
 * capture begins, so this directory cannot accumulate images.
 */
class PlateCaptureStore(context: Context) {
    private val directory = File(context.cacheDir, "captures")

    fun stage(source: InputStream, mimeType: String): String? {
        clear()
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val id = UUID.randomUUID().toString().replace("-", "") +
            if (mimeType == "image/png") ".png" else ".jpg"
        val target = File(directory, id)
        val copied = runCatching {
            source.use { input -> target.outputStream().use(input::copyTo) }
            target.length()
        }.getOrDefault(0L)
        // A zero-length file is a camera app that reported success without
        // writing anything; the page is better told nothing arrived.
        if (copied !in 1..MAX_PHOTO_BYTES.toLong()) {
            target.delete()
            return null
        }
        return id
    }

    fun open(id: String): Pair<String, InputStream>? {
        if (!CAPTURE_ID.matches(id)) return null
        val file = File(directory, id)
        if (!file.isFile) return null
        val mimeType = if (id.endsWith(".png")) "image/png" else "image/jpeg"
        return mimeType to FileInputStream(file)
    }

    /** The camera writes here too, so this is also where a capture the camera
     * app abandoned half-written is cleaned up. */
    fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    fun cameraTarget(): File? {
        clear()
        if (!directory.isDirectory && !directory.mkdirs()) return null
        return File(directory, "camera-${System.currentTimeMillis()}.tmp")
    }
}
