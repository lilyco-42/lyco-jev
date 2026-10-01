package com.jev.probe.capture.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YOLO output decoder is the part of goal 2 most likely to be silently
 * wrong: a transposed tensor does not throw, it produces confident nonsense.
 * These tests pin both layouts and the real YOLOX shape.
 */
class YoloDecodeTest {

    /** YOLOv8/v11 export: [1, 4 + classes, anchors]. */
    private fun attrsMajor(classes: Int, anchors: Int) = Array(4 + classes) { FloatArray(anchors) }

    /** YOLOX / PP-YOLOE export: [1, anchors, 5 + classes]. */
    private fun anchorsMajor(classes: Int, anchors: Int) = Array(anchors) { FloatArray(5 + classes) }

    @Test
    fun yolov8AttributesMajorDecodes() {
        val rows = attrsMajor(classes = 2, anchors = 3)
        // anchor 0: cx=100 cy=120 w=20 h=10, class 1 at 0.90
        rows[0][0] = 100f; rows[1][0] = 120f; rows[2][0] = 20f; rows[3][0] = 10f
        rows[4][0] = 0.10f; rows[5][0] = 0.90f

        val out = YoloDecode.decode(arrayOf<Any>(rows), classes = 2)

        assertEquals(1, out.size)
        assertEquals(1, out[0].cls)
        assertEquals(0.90f, out[0].score, 1e-5f)
        assertEquals(100f, out[0].cx, 1e-5f)
        assertEquals(10f, out[0].h, 1e-5f)
    }

    @Test
    fun yoloxAnchorsMajorUsesObjectness() {
        val rows = anchorsMajor(classes = 2, anchors = 3)
        // anchor 1: cx=200 cy=300 w=40 h=20, objectness 0.9, class 1 at 0.8 -> 0.72
        rows[1][0] = 200f; rows[1][1] = 300f; rows[1][2] = 40f; rows[1][3] = 20f
        rows[1][4] = 0.9f; rows[1][5] = 0.1f; rows[1][6] = 0.8f

        val out = YoloDecode.decode(arrayOf<Any>(rows), classes = 2)

        assertEquals(1, out.size)
        assertEquals(1, out[0].cls)
        assertEquals(0.72f, out[0].score, 1e-4f)
        assertEquals(200f, out[0].cx, 1e-4f)
    }

    /**
     * Regression: with the real YOLOX ratio (rows 100 > cols 85) an earlier
     * version inferred the axis order from the size ratio, used the row count as
     * the stride, and iterated only 85 of the 100 anchors. It must read every
     * anchor with the row length as the stride.
     */
    @Test
    fun yoloxRealisticShapeReadsEveryAnchorWithRowStride() {
        val classes = 80
        val anchors = 100
        val rows = anchorsMajor(classes, anchors)
        rows[42][0] = 320f; rows[42][1] = 240f; rows[42][2] = 100f; rows[42][3] = 50f
        rows[42][4] = 0.9f
        rows[42][5 + 7] = 0.85f     // class 7

        val out = YoloDecode.decode(arrayOf<Any>(rows), classes)

        assertEquals(1, out.size)
        assertEquals(7, out[0].cls)
        assertEquals(320f, out[0].cx, 1e-3f)
        assertEquals(0.9f * 0.85f, out[0].score, 1e-4f)
    }

    @Test
    fun nmsDropsOverlappingBoxesOfTheSameClass() {
        val a = YoloDecode.Box(100f, 100f, 50f, 50f, 0.9f, 1)
        val b = YoloDecode.Box(102f, 101f, 50f, 50f, 0.8f, 1)
        val c = YoloDecode.Box(400f, 400f, 50f, 50f, 0.7f, 1)

        val kept = YoloDecode.nms(listOf(a, b, c), 0.45f)

        assertEquals(2, kept.size)
        assertEquals(0.9f, kept[0].score, 1e-5f)
    }

    /** Two different objects can legitimately occupy the same pixels. */
    @Test
    fun nmsKeepsOverlappingBoxesOfDifferentClasses() {
        val a = YoloDecode.Box(100f, 100f, 50f, 50f, 0.9f, 1)
        val b = YoloDecode.Box(100f, 100f, 50f, 50f, 0.8f, 2)
        assertEquals(2, YoloDecode.nms(listOf(a, b), 0.45f).size)
    }

    @Test
    fun unreadableOutputYieldsNothingInsteadOfThrowing() {
        assertTrue(YoloDecode.decode(null, 2).isEmpty())
        assertTrue(YoloDecode.decode("nonsense", 2).isEmpty())
        assertTrue(YoloDecode.decode(Array<Any>(1) { arrayOf<Any>() }, 2).isEmpty())
        assertTrue(YoloDecode.decode(FloatArray(7), 2).isEmpty())   // 7 % 6 != 0
    }
}
