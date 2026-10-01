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
    /**
     * Sources in order of preference.
     *
     * The lain42 mirror (Aliyun OSS behind dl.lain42.top) is first because
     * HuggingFace is slow or unreachable from mainland China; upstream HF stays
     * as the fallback. Both serve the same bytes - the mirror path pins the
     * revision the calibration was measured against, so the app cannot silently
     * drift onto a newer weight file.
     */
    val DOWNLOAD_URLS = listOf(
        "https://dl.lain42.top/models/hf/chaoliangUNSW/Jev-Style-0.8B-Decision-v3-GGUF/resolve/" +
            "edf37c26a1098f83cf4264b8adbe0dca2d2ebb0c/Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf",
        "https://huggingface.co/chaoliangUNSW/Jev-Style-0.8B-Decision-v3-GGUF/resolve/main/" +
            "Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf",
    )

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
                Log.i(TAG, "no bundled GGUF; downloading from " + DOWNLOAD_URLS.first())
                download(dst)
            }
            cached = dst
            return dst
        }
    }

    fun isPresent(ctx: Context): Boolean = File(ctx.filesDir, FILE_NAME).isFile

    /** Tries each source in turn; only a fully written file is kept. */
    private fun download(dst: File) {
        var last: Exception? = null
        for (url in DOWNLOAD_URLS) {
            val tmp = File(dst.absolutePath + ".part")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output) } }
                if (!tmp.renameTo(dst)) throw java.io.IOException("could not move " + tmp + " to " + dst)
                Log.i(TAG, "downloaded from " + url)
                return
            } catch (e: Exception) {
                Log.w(TAG, "source failed (" + url + "): " + e.message)
                last = e
            }
        }
        throw java.io.IOException("every download source failed", last)
    }
}
