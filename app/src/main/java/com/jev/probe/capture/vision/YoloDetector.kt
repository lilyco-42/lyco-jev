package com.jev.probe.capture.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.FloatBuffer

/**
 * Goal 2, first half: YOLO object detection on the on-device ONNX Runtime.
 *
 * OCR answers "what words are in this image"; YOLO answers "what things are in
 * it, and where". A chat image often needs both, because a sticker, a product
 * photo or a payment code carries little or no useful text yet changes what the
 * other person is saying.
 *
 * This class only does the Android plumbing: bitmap -> letterboxed CHW tensor,
 * then mapping the decoded boxes back to screen coordinates. The output layout
 * arithmetic lives in [YoloDecode], which has no Android types and is unit
 * tested, because a wrong axis order fails silently rather than loudly.
 *
 * License note: Ultralytics YOLOv8/v11 weights are AGPL-3.0. Shipping them in
 * this MIT fork would relicense the whole app, so the checkpoint is supplied by
 * the builder (tools/fetch_vision_assets.ps1 takes -ModelUrl); the Apache-2.0
 * YOLOX export is the documented default.
 */
class YoloDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val names: Array<String>
) {

    data class Detection(val label: String, val score: Float, val box: RectF)

    /** Raw graph output plus the letterbox mapping, for shape assertions in tests. */
    data class Forward(val raw: Any?, val scale: Float, val padX: Int, val padY: Int)

    private val inputName: String = session.inputNames.first()

    /**
     * Input size comes from the graph, not from a constant. The first checkpoint
     * we shipped is exported at 544x544, and feeding 640 fails at run time with
     * ORT_INVALID_ARGUMENT - a dead vision path that no compile or decoder test
     * would ever reveal.
     */
    private val inputShape: LongArray =
        ((session.inputInfo[inputName]?.info) as? ai.onnxruntime.TensorInfo)?.shape
            ?: longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())

    val inputH: Int = inputShape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: INPUT
    val inputW: Int = inputShape.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: INPUT

    /** Number of classes the loaded label file declares. */
    val classCount: Int get() = names.size

    /**
     * Runs the graph on a letterboxed copy of [bitmap]. Split out from [detect]
     * so an instrumented test can check that the real tensor shape is one the
     * decoder actually supports.
     */
    fun forward(bitmap: Bitmap): Forward {
        val scale = minOf(inputW / bitmap.width.toFloat(), inputH / bitmap.height.toFloat())
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val padX = (inputW - newW) / 2
        val padY = (inputH - newH) / 2

        val square = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(bitmap, null, RectF(padX.toFloat(), padY.toFloat(),
                (padX + newW).toFloat(), (padY + newH).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        val plane = inputW * inputH
        val pixels = IntArray(plane)
        square.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        square.recycle()
        val chw = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            chw[i] = ((p shr 16) and 0xFF) / 255f
            chw[plane + i] = ((p shr 8) and 0xFF) / 255f
            chw[2 * plane + i] = (p and 0xFF) / 255f
        }

        val shape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
        val raw = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape).use { t ->
            session.run(mapOf(inputName to t)).use { r -> r[0].value }
        }
        return Forward(raw, scale, padX, padY)
    }

    fun detect(bitmap: Bitmap, confThreshold: Float = 0.35f, iouThreshold: Float = 0.45f): List<Detection> {
        val f = forward(bitmap)
        val scale = f.scale
        val padX = f.padX
        val padY = f.padY
        return YoloDecode.decode(f.raw, names.size, confThreshold, iouThreshold).map { b ->
            Detection(names[b.cls], b.score, RectF(
                (b.cx - b.w / 2 - padX) / scale,
                (b.cy - b.h / 2 - padY) / scale,
                (b.cx + b.w / 2 - padX) / scale,
                (b.cy + b.h / 2 - padY) / scale))
        }
    }

    companion object {
        private const val TAG = "JEVVISION"
        const val INPUT = 640

        /** Supplied by tools/fetch_vision_assets.ps1. */
        const val MODEL_ASSET = "models/yolo/detector.onnx"
        private const val LABELS_ASSET = "models/yolo/coco_labels.txt"

        @Volatile private var instance: YoloDetector? = null
        @Volatile private var loadError: String? = null

        fun loadError(): String? = loadError

        fun get(ctx: Context): YoloDetector? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return try {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    val session = env.createSession(readAsset(ctx, MODEL_ASSET), opts)
                    YoloDetector(env, session, readLabels(ctx)).also { instance = it }
                } catch (t: Throwable) {
                    loadError = t.javaClass.simpleName + ": " + t.message
                    Log.w(TAG, "YOLO unavailable: " + loadError)
                    null
                }
            }
        }

        private fun readAsset(ctx: Context, path: String): ByteArray =
            ctx.assets.open(path).use { input: InputStream ->
                ByteArrayOutputStream(1 shl 20).also { out -> input.copyTo(out) }.toByteArray()
            }

        private fun readLabels(ctx: Context): Array<String> =
            ctx.assets.open(LABELS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.map { it.trim() }.toList().toTypedArray()
            }
    }
}
