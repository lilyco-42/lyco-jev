package com.jev.probe.capture.vision

import android.graphics.Bitmap
import com.jev.probe.capture.ocr.OcrLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Goal 2, second half: fuse the two cheap signals into one description a judge
 * model can use.
 *
 * OCR gives lines of text; YOLO gives labelled objects. Neither alone describes a
 * chat image: a payment-code screenshot has almost no words, and "帮我看看这个"
 * next to a product photo has no useful words either. Pairing OCR lines to the
 * object that contains them yields "二维码（含文字：收款码）" instead of two
 * disconnected lists.
 *
 * The assembly and its geometry are Android-free ([Rect4], [describe]) so they
 * can be unit tested; only the bitmap overload touches Android types.
 */
object VisionFusion {

    /** How much of a text line must sit inside an object for it to be "in" it. */
    const val INSIDE_FRACTION = 0.5f

    private const val MAX_NESTED = 4

    /** Android-free rectangle, so the pairing geometry is testable off-device. */
    data class Rect4(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Fraction of [inner]'s area that lies inside this rectangle, 0..1. */
        fun coverageOf(inner: Rect4): Float {
            if (inner.width <= 0f || inner.height <= 0f) return 0f
            val w = minOf(right, inner.right) - maxOf(left, inner.left)
            val h = minOf(bottom, inner.bottom) - maxOf(top, inner.top)
            if (w <= 0f || h <= 0f) return 0f
            return (w * h) / (inner.width * inner.height)
        }
    }

    /**
     * Pure assembly. [objects] is (label, box) in the same coordinate space as
     * [texts].
     */
    fun describe(objects: List<Pair<String, Rect4>>, texts: List<Pair<String, Rect4>>): String {
        val sb = StringBuilder()

        if (objects.isNotEmpty()) {
            val counts = LinkedHashMap<String, Int>()
            for ((label, _) in objects) counts[label] = (counts[label] ?: 0) + 1
            sb.append("图中的物体：")
            sb.append(counts.entries.joinToString("、") { it.key + "×" + it.value })
        }

        if (texts.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("；")
            sb.append("图中的文字：")
            sb.append(texts.joinToString(" / ") { it.first })
        }

        val nested = ArrayList<String>()
        for ((label, box) in objects) {
            val inside = texts.filter { box.coverageOf(it.second) > INSIDE_FRACTION }.map { it.first }
            if (inside.isNotEmpty()) nested.add(label + "（" + inside.joinToString(" / ") + "）")
            if (nested.size >= MAX_NESTED) break
        }
        if (nested.isNotEmpty()) sb.append("；对应关系：").append(nested.joinToString("、"))

        return if (sb.isEmpty()) "（图中未识别出文字或物体）" else sb.toString()
    }

    /** Device overload: runs the detector, then hands plain geometry to [describe]. */
    fun describe(bitmap: Bitmap, lines: List<OcrLine>, detector: YoloDetector?): String {
        val objects = runCatching { detector?.detect(bitmap) }.getOrNull().orEmpty()
            .map { it.label to Rect4(it.box.left, it.box.top, it.box.right, it.box.bottom) }
        val texts = lines.map {
            it.text to Rect4(it.bounds.left.toFloat(), it.bounds.top.toFloat(),
                it.bounds.right.toFloat(), it.bounds.bottom.toFloat())
        }
        return describe(objects, texts)
    }

    /** A compact JSON form for the overlay and the debug log. */
    fun describeJson(bitmap: Bitmap, lines: List<OcrLine>, detector: YoloDetector?): JSONObject {
        val objects = JSONArray()
        runCatching { detector?.detect(bitmap) }.getOrNull()?.forEach { d ->
            objects.put(JSONObject().put("label", d.label).put("score", d.score.toDouble()))
        }
        val texts = JSONArray()
        lines.forEach { texts.put(it.text) }
        return JSONObject().put("objects", objects).put("texts", texts)
    }
}
