package com.jev.probe.jev

import android.content.Context
import java.io.File

/** (bytesDone, bytesTotal); total is -1 when the source does not report a size. */
typealias ProgressListener = (Long, Long) -> Unit

/**
 * The judge weight: Jev-Style-0.8B-Decision-v3, Q4_K_M, 0.53 GB, Apache-2.0.
 *
 * File handling lives in [ModelStore] now that the drafting route needs the same
 * treatment for a second weight; this object is just the judge's identity.
 */
object LocalJevModel {

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

    private const val MIN_BYTES = 100L * 1024 * 1024

    private val store = ModelStore(ASSET_PATH, FILE_NAME, DOWNLOAD_URLS, MIN_BYTES)

    /** @param onStage / @param onProgress see [ModelStore.ensure]. */
    fun ensure(
        ctx: Context,
        onStage: ((ModelStore.Stage) -> Unit)? = null,
        onProgress: ProgressListener? = null,
    ): File = store.ensure(ctx, onStage, onProgress)

    fun isPresent(ctx: Context): Boolean = store.isPresent(ctx)
}
