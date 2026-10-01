package com.jev.probe.capture.vision

/**
 * Pure decoder for YOLO-family detection outputs: no Android types, so the
 * layout arithmetic is unit-testable without a device.
 *
 * Two COCO layouts are supported, and the axis order is decided by the attribute
 * count rather than by guessing from the size ratio:
 *
 *   - YOLOv8 / v11: `[1, 4 + classes, anchors]`   - no objectness
 *   - YOLOX / PP-YOLOE: `[1, anchors, 5 + classes]` - objectness at attribute 4
 *
 * Getting this wrong is silent: an anchors-major tensor read with the
 * attributes-major stride produces confident nonsense instead of an error.
 */
object YoloDecode {

    /** One detection in model-input pixels. */
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float, val cls: Int)

    /**
     * Row-major view of the output. `flat[row * cols + col]` is the only
     * indexing that holds for both layouts, because the stride between
     * consecutive anchors is always the row length.
     */
    class Layout(val flat: FloatArray, val rows: Int, val cols: Int, val attrsMajor: Boolean) {
        val attrCount: Int get() = if (attrsMajor) rows else cols
        val anchors: Int get() = if (attrsMajor) cols else rows

        fun at(attr: Int, anchor: Int): Float =
            if (attrsMajor) flat[attr * cols + anchor] else flat[anchor * cols + attr]
    }

    /**
     * True when attributes are the outer dimension. Exact when the attribute
     * count matches a known layout; otherwise fall back to "attributes are the
     * smaller axis", which holds for every real COCO export.
     */
    fun attrsMajor(rows: Int, cols: Int, classes: Int): Boolean = when {
        rows == 4 + classes || rows == 5 + classes -> true
        cols == 4 + classes || cols == 5 + classes -> false
        else -> rows <= cols
    }

    fun layout(raw: Any?, classes: Int): Layout? {
        when (raw) {
            is Array<*> -> {
                if (raw.isEmpty()) return null
                @Suppress("UNCHECKED_CAST")
                val rows = raw[0] as? Array<Any?> ?: return null
                if (rows.isEmpty()) return null
                val first = rows[0] as? FloatArray ?: return null
                val r = rows.size
                val c = first.size
                val flat = FloatArray(r * c)
                for (i in 0 until r) {
                    @Suppress("UNCHECKED_CAST")
                    (rows[i] as? FloatArray)?.copyInto(flat, i * c) ?: return null
                }
                return Layout(flat, r, c, attrsMajor(r, c, classes))
            }
            is FloatArray -> {
                // Flat output with no shape: assume the attributes-major layout.
                val feats = 4 + classes
                if (raw.isEmpty() || raw.size % feats != 0) return null
                return Layout(raw, feats, raw.size / feats, attrsMajor = true)
            }
            else -> return null
        }
    }

    fun decode(raw: Any?, classes: Int, conf: Float = 0.35f, iouThreshold: Float = 0.45f): List<Box> {
        if (classes <= 0) return emptyList()
        val l = layout(raw, classes) ?: return emptyList()
        val withObjectness = l.attrCount == 5 + classes
        val classOffset = if (withObjectness) 5 else 4
        if (l.attrCount < classOffset + classes) return emptyList()

        val found = ArrayList<Box>(64)
        for (a in 0 until l.anchors) {
            val objectness = if (withObjectness) l.at(4, a) else 1f
            if (objectness < conf) continue
            var best = -1
            var bestScore = conf
            for (c in 0 until classes) {
                val s = l.at(classOffset + c, a) * objectness
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) continue
            found.add(Box(l.at(0, a), l.at(1, a), l.at(2, a), l.at(3, a), bestScore, best))
        }
        return nms(found, iouThreshold)
    }

    fun nms(list: List<Box>, iouThreshold: Float): List<Box> {
        val sorted = list.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Box>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { it.cls == best.cls && iou(best, it) > iouThreshold }
        }
        return keep
    }

    fun iou(a: Box, b: Box): Float {
        val ix = maxOf(0f, minOf(a.cx + a.w / 2, b.cx + b.w / 2) - maxOf(a.cx - a.w / 2, b.cx - b.w / 2))
        val iy = maxOf(0f, minOf(a.cy + a.h / 2, b.cy + b.h / 2) - maxOf(a.cy - a.h / 2, b.cy - b.h / 2))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return if (union <= 0f) 0f else inter / union
    }
}
