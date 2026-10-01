package com.jev.probe.capture.vision

import com.jev.probe.capture.vision.VisionFusion.Rect4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal 2's fusion step: turning "objects" and "text lines" into one sentence the
 * judge model can read. Geometry and assembly are Android-free, so this runs in
 * the JVM; the bitmap overload is covered by VisionInstrumentedTest.
 */
class VisionFusionTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = Rect4(l, t, r, b)

    @Test
    fun countsRepeatedLabels() {
        val out = VisionFusion.describe(
            listOf("手机" to box(0f, 0f, 10f, 10f), "手机" to box(20f, 0f, 30f, 10f)),
            emptyList())
        assertEquals("图中的物体：手机×2", out)
    }

    @Test
    fun listsTextLinesInOrder() {
        val out = VisionFusion.describe(
            emptyList(),
            listOf("你好" to box(0f, 0f, 10f, 10f), "在吗" to box(0f, 20f, 10f, 30f)))
        assertEquals("图中的文字：你好 / 在吗", out)
    }

    @Test
    fun pairsATextLineThatSitsInsideAnObject() {
        val out = VisionFusion.describe(
            listOf("二维码" to box(100f, 100f, 200f, 200f)),
            listOf("收款码" to box(110f, 120f, 190f, 160f)))
        assertTrue("expected a pairing, got: " + out, out.contains("二维码（收款码）"))
    }

    /** Merely overlapping an object is not being inside it. */
    @Test
    fun doesNotPairATextLineThatOnlyOverlapsSlightly() {
        val out = VisionFusion.describe(
            listOf("二维码" to box(100f, 100f, 200f, 200f)),
            // only a sliver of the line's area falls inside the square
            listOf("收款码" to box(195f, 100f, 295f, 200f)))
        assertFalse("should not pair: " + out, out.contains("二维码（"))
        assertTrue(out.contains("图中的文字：收款码"))
    }

    @Test
    fun coverageIsZeroWhenDisjoint() {
        assertEquals(0f, box(0f, 0f, 10f, 10f).coverageOf(box(50f, 50f, 60f, 60f)), 1e-6f)
    }

    @Test
    fun coverageIsOneWhenFullyContained() {
        assertEquals(1f, box(0f, 0f, 100f, 100f).coverageOf(box(10f, 10f, 20f, 20f)), 1e-6f)
    }

    @Test
    fun emptyInputSaysSoRatherThanReturningAnEmptyString() {
        val out = VisionFusion.describe(emptyList(), emptyList())
        assertTrue("should be explicit, got: " + out, out.isNotBlank())
        assertTrue(out.contains("未识别"))
    }

    @Test
    fun capsTheNumberOfPairings() {
        val objects = (0 until 8).map { "物" + it to box(0f, (it * 100).toFloat(), 100f, (it * 100 + 90).toFloat()) }
        val texts = (0 until 8).map { "文" + it to box(10f, (it * 100 + 10).toFloat(), 90f, (it * 100 + 50).toFloat()) }
        val out = VisionFusion.describe(objects, texts)
        val paired = out.substringAfter("对应关系：").split("、").size
        assertEquals(4, paired)
    }
}
