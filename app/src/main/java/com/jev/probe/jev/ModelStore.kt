package com.jev.probe.jev

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Makes one bundled GGUF available as a real file, because llama.cpp mmaps a
 * path and an APK asset is not one.
 *
 * Order: already-extracted file -> bundled asset -> first-run download.
 *
 * This used to live inside [LocalJevModel]; the drafting route added a second
 * weight (the judge cannot generate - see jev_jni.cpp), and both need exactly
 * this logic, so it moved here instead of being copied.
 */
class ModelStore(
    private val assetPath: String,
    private val fileName: String,
    private val downloadUrls: List<String>,
    /** Anything smaller than this is a half-written file, not a model. */
    private val minBytes: Long,
) {

    @Volatile private var cached: File? = null

    /** Where the bytes are coming from, so the caller can say something true. */
    enum class Stage {
        /** Unpacking the copy that shipped inside the APK - no network involved. */
        EXTRACTING,

        /** No bundled copy; pulling it down. */
        DOWNLOADING,
    }

    /**
     * @param onStage called once, before the first byte moves.
     * @param onProgress called during the one-off copy/download, because half a
     *   gigabyte with no feedback is indistinguishable from a hang.
     */
    fun ensure(
        ctx: Context,
        onStage: ((Stage) -> Unit)? = null,
        onProgress: ProgressListener? = null,
    ): File {
        cached?.let { if (it.isFile) return it }
        synchronized(this) {
            cached?.let { if (it.isFile) return it }
            val dst = File(ctx.filesDir, fileName)
            if (dst.isFile && dst.length() > minBytes) {
                cached = dst
                return dst
            }
            val asset = try {
                ctx.assets.open(assetPath)
            } catch (e: Exception) {
                null
            }
            if (asset != null) {
                val total = assetLength(ctx)
                // Fail with a sentence a human can act on. Without this, a phone
                // short of half a gigabyte just throws ENOSPC from inside the copy
                // and the caller reports "失败：No space left on device".
                val need = if (total > 0) total else minBytes
                val free = dst.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (free in 1 until need + (64L shl 20)) {
                    throw java.io.IOException(
                        "手机存储不足：端侧模型需要约 " + (need / (1024 * 1024)) +
                            " MB，当前可用 " + (free / (1024 * 1024)) + " MB"
                    )
                }
                Log.i(TAG, "extracting bundled GGUF -> " + dst.absolutePath + " (" + total + " bytes)")
                onStage?.invoke(Stage.EXTRACTING)
                asset.use { input ->
                    FileOutputStream(dst).use { output -> copyWithProgress(input, output, total, onProgress) }
                }
            } else {
                Log.i(TAG, "no bundled $fileName; downloading from " + downloadUrls.first())
                onStage?.invoke(Stage.DOWNLOADING)
                download(dst, onProgress)
            }
            cached = dst
            return dst
        }
    }

    fun isPresent(ctx: Context): Boolean = File(ctx.filesDir, fileName).isFile

    /**
     * Length of the bundled asset, or -1 when it is compressed.
     *
     * openFd() only works on STORED zip entries, which is why build.gradle.kts sets
     * noCompress for gguf: without that the size is unknown and the progress bar
     * could only ever show bytes moved.
     */
    private fun assetLength(ctx: Context): Long = try {
        ctx.assets.openFd(assetPath).use { it.length }
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

    /** Tries each source in turn; only a fully written file is kept. */
    private fun download(dst: File, onProgress: ProgressListener?) {
        var last: Exception? = null
        for (url in downloadUrls) {
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

    companion object {
        private const val TAG = "JEVLOCAL"
    }
}
