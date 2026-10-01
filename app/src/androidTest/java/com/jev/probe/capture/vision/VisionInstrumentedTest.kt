package com.jev.probe.capture.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal 2's other half: does the real ONNX graph load and produce a tensor the
 * decoder actually understands?
 *
 * [YoloDecodeTest] pins the arithmetic against synthetic tensors. Only this test
 * can catch a mismatch between that arithmetic and what the shipped model emits,
 * which is exactly the class of bug that made the YOLOX path silently useless.
 */
@RunWith(AndroidJUnit4::class)
class VisionInstrumentedTest {

    private val tag = "JEVVIS-IT"

    @Test
    fun detectorLoadsAndItsOutputMatchesASupportedLayout() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = YoloDetector.get(ctx)
        assertNotNull("YOLO model did not load: " + YoloDetector.loadError(), detector)
        detector!!

        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(100f, 100f, 300f, 280f, Paint().apply { color = Color.RED })
        }

        val forward = detector.forward(bmp)
        assertNotNull("graph produced no output", forward.raw)

        val layout = YoloDecode.layout(forward.raw, detector.classCount)
        assertNotNull("output shape is not a supported YOLO layout", layout)
        Log.i(tag, "graph input " + detector.inputW + "x" + detector.inputH)
        Log.i(tag, "raw rows=" + layout!!.rows + " cols=" + layout.cols +
            " attrsMajor=" + layout.attrsMajor + " attrs=" + layout.attrCount +
            " anchors=" + layout.anchors + " classes=" + detector.classCount)

        // The attribute count must be exactly one of the two known layouts.
        assertTrue("attribute count " + layout.attrCount + " is neither 4+nc nor 5+nc",
            layout.attrCount == 4 + detector.classCount || layout.attrCount == 5 + detector.classCount)

        val found = detector.detect(bmp)
        Log.i(tag, "detections=" + found.map { it.label + "@" + it.score } +
            " letterbox scale=" + forward.scale + " pad=(" + forward.padX + "," + forward.padY + ")")

        // The letterbox must map model pixels back inside the source image.
        for (d in found) {
            assertTrue("box outside the image: " + d.box,
                d.box.left >= -2f && d.box.top >= -2f &&
                    d.box.right <= bmp.width + 2f && d.box.bottom <= bmp.height + 2f)
        }
    }
}
