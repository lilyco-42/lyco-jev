package com.jev.probe.jev

import android.content.Context
import java.io.File

/**
 * The drafting weight: Qwen3.5-0.8B-Instruct, Q4_K_M, 507.8 MiB (532,517,120 B),
 * Apache-2.0.
 *
 * Why a second model at all: Jev-Style was trained down to a yes/no decision head
 * and **cannot generate text** - a 160-token sample came back as "yes no no no No
 * yes no no ...", every token a yes/no. The candidate replies therefore need a
 * generative weight, and this is the smallest one in the same family: Qwen3.5-0.8B
 * is the exact base Jev-Style-0.8B-Decision-v3 was fine-tuned from, so tokenizer
 * and architecture are already proven against this llama.cpp build.
 *
 * It is a big addition - the APK goes from ~594 MiB to ~1.1 GB - which is the
 * price of "everything on-device, offline first".
 */
object LocalReplyModel {

    const val ASSET_PATH = "models/reply/Qwen3.5-0.8B-Q4_K_M.gguf"
    const val FILE_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"

    /** sha256 of the exact bytes this build was measured against (unsloth Q4_K_M). */
    const val SHA256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"

    /** Bytes as published by the upstream repo; used for the size sanity check. */
    const val BYTES = 532_517_120L

    /**
     * HuggingFace first: unlike the judge weight, this file is not (yet) cached in
     * the lain42 mirror - the mirror path answered 404 when this was written - so
     * listing it first would only add a failed round trip.
     */
    val DOWNLOAD_URLS = listOf(
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
        "https://dl.lain42.top/models/hf/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
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
