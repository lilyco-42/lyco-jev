package com.jev.probe.jev

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** (bytesDone, bytesTotal); total is -1 when the source does not report a size. */
typealias ProgressListener = (Long, Long) -> Unit

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

    /**
     * @param onProgress called during the one-off copy/download, because half a
     *   gigabyte with no feedback is indistinguishable from a hang.
     */
    fun ensure(ctx: Context, onProgress: ProgressListener? = null): File {
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
                val total = assetLength(ctx)
                // Fail with a sentence a human can act on. Without this, a phone
                // short of half a gigabyte just throws ENOSPC from inside the copy
                // and the caller reports "失败：No space left on device".
                val need = if (total > 0) total else 600L * 1024 * 1024
                val free = dst.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (free in 1 until need + (64L shl 20)) {
                    throw java.io.IOException(
                        "手机存储不足：端侧模型需要约 " + (need / (1024 * 1024)) +
                            " MB，当前可用 " + (free / (1024 * 1024)) + " MB"
                    )
                }
                Log.i(TAG, "extracting bundled GGUF -> " + dst.absolutePath + " (" + total + " bytes)")
                asset.use { input ->
                    FileOutputStream(dst).use { output -> copyWithProgress(input, output, total, onProgress) }
                }
            } else {
                Log.i(TAG, "no bundled GGUF; downloading from " + DOWNLOAD_URLS.first())
                download(dst, onProgress)
            }
            cached = dst
            return dst
        }
    }

    /**
     * Length of the bundled asset, or -1 when it is compressed.
     *
     * openFd() only works on STORED zip entries, which is why build.gradle.kts sets
     * noCompress for gguf: without that the size is unknown and the progress bar
     * could only ever show bytes moved.
     */
    private fun assetLength(ctx: Context): Long = try {
        ctx.assets.openFd(ASSET_PATH).use { it.length }
    } catch (e: Exception) {
        -1L
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: ProgressListener?
    ) {
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var reported = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            // One callback per ~8 MiB keeps a UI update loop from costing more than
            // the copy itself.
            if (onProgress != null && done - reported >= (8L shl 20)) {
                reported = done
                onProgress(done, total)
            }
        }
        output.flush()
        onProgress?.invoke(done, total)
    }

    fun isPresent(ctx: Context): Boolean = File(ctx.filesDir, FILE_NAME).isFile

    /** Tries each source in turn; only a fully written file is kept. */
    private fun download(dst: File, onProgress: ProgressListener?) {
        var last: Exception? = null
        for (url in DOWNLOAD_URLS) {
            val tmp = File(dst.absolutePath + ".part")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output -> copyWithProgress(input, output, total, onProgress) }
                }
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
