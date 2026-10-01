package com.jev.probe.jev

/**
 * JNI surface of the bundled llama.cpp scorer (\`app/src/main/cpp/jev_jni.cpp\`).
 *
 * The native side owns the model, the tokenizer and one forward pass; it never
 * generates text. Kotlin owns rendering and calibration, because the reference
 * "macjev-render-v1" layout is string work and belongs where it can be unit
 * tested without a device.
 */
object LocalJevNative {

    @Volatile private var loaded = false
    private var loadError: String? = null

    /** Loads libjevjni.so once; returns null on success or the reason on failure. */
    @Synchronized fun ensureLoaded(): String? {
        if (loaded) return null
        loadError?.let { return it }
        return try {
            System.loadLibrary("jevjni")
            nativeBackendInit()
            loaded = true
            null
        } catch (t: Throwable) {
            loadError = t.javaClass.simpleName + ": " + t.message
            loadError
        }
    }

    external fun nativeBackendInit()
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeTokenize(handle: Long, text: String): IntArray?
    external fun nativeTokenId(handle: Long, text: String): Int
    external fun nativeDecide(handle: Long, ids: IntArray, slots: IntArray, yesId: Int, noId: Int): FloatArray?
}
