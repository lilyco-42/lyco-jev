package com.jev.probe.jev

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Makes the Jev-Style GGUF available as a real file, because llama.cpp mmaps a
 * path and an APK asset is not one.
 *
 * Order: already-extracted file -> bundled asset -> first-run download. The
 * 4-bit build is 0.53 GB, so shipping it inside the APK is a choice the builder
 * makes; the download path exists for builds that leave it out.
 */
object LocalJevModel {

    private const val TAG = "JEVLOCAL"
    const val ASSET_PATH = "models/jev-style/Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf"
    const val FILE_NAME = "Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf"
    const val DOWNLOAD_URL =
        "https://huggingface.co/chaoliangUNSW/Jev-Style-0.8B-Decision-v3-GGUF/resolve/main/" +
            "Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf"

    @Volatile private var cached: File? = null

    fun ensure(ctx: Context): File {
        cached?.let { if (it.isFile) return it }
        synchronized(this) {
            cached?.let { if (it.isFile) return it }
            val dst = File(ctx.filesDir, FILE_NAME)
            if (dst.isFile && dst.length() > 100L * 1024 * 1024) {
                cached = dst
                return dst
            }
            val asset = try {
                ctx.assets.open(ASSET_PATH)
            } catch (e: Exception) {
                null
            }
            if (asset != null) {
                Log.i(TAG, "extracting bundled GGUF -> " + dst.absolutePath)
                asset.use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
            } else {
                Log.i(TAG, "no bundled GGUF; downloading " + DOWNLOAD_URL)
                download(dst)
            }
            cached = dst
            return dst
        }
    }

    fun isPresent(ctx: Context): Boolean = File(ctx.filesDir, FILE_NAME).isFile

    private fun download(dst: File) {
        val tmp = File(dst.absolutePath + ".part")
        val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output) } }
        if (!tmp.renameTo(dst)) throw java.io.IOException("could not move " + tmp + " to " + dst)
    }
}
