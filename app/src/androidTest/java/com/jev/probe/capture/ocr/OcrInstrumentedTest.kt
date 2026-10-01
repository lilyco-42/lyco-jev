package com.jev.probe.capture.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jev.probe.capture.vision.VisionFusion
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Goal 2's OCR half, on device. Only YOLO had a real test before; the OCR path -
 * bundled ML Kit Chinese recognizer, crop offset, coordinate mapping - had none,
 * and it is the half that actually carries the words in a chat screenshot.
 *
 * The image is drawn here rather than shipped, so the expected text is known.
 */
@RunWith(AndroidJUnit4::class)
class OcrInstrumentedTest {

    private val tag = "JEVOCR-IT"

    private fun render(text: String, width: Int = 960, height: Int = 260): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText(text, 40f, 160f, Paint().apply {
                color = Color.BLACK
                textSize = 96f
                isAntiAlias = true
            })
        }
        return bmp
    }

    private fun recognizeBlocking(ocr: MlKitOcr, bmp: Bitmap, timeoutSec: Long = 60): List<OcrLine> {
        var out: List<OcrLine> = emptyList()
        val latch = CountDownLatch(1)
        // The engine posts its callback to the main thread; the test runs on the
        // instrumentation thread, so awaiting here cannot deadlock.
        ocr.recognize(bmp, null) { lines -> out = lines; latch.countDown() }
        assertTrue("OCR callback never fired", latch.await(timeoutSec, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun bundledChineseRecognizerReadsRenderedText() {
        val bmp = render("你好在吗")
        val lines = recognizeBlocking(MlKitOcr(), bmp)
        Log.i(tag, "lines=" + lines.map { it.text + "@" + it.bounds })

        assertTrue("recognizer returned nothing; bundled Chinese model may be missing", lines.isNotEmpty())
        val joined = lines.joinToString("") { it.text }
        Log.i(tag, "joined=[" + joined + "]")

        // ML Kit is not a diff tool: require that it recovered most of the string
        // rather than an exact match.
        val expected = "你好在吗"
        val hits = expected.count { c -> joined.contains(c) }
        assertTrue("only " + hits + "/4 characters recovered from [" + joined + "]", hits >= 3)

        // Boxes must land inside the bitmap.
        for (l in lines) {
            assertTrue("box outside the bitmap: " + l.bounds,
                l.bounds.left >= -2 && l.bounds.top >= -2 &&
                    l.bounds.right <= bmp.width + 2 && l.bounds.bottom <= bmp.height + 2)
        }
    }

    @Test
    fun blankImageYieldsNoLinesInsteadOfFailing() {
        val blank = Bitmap.createBitmap(480, 200, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.WHITE)
        val lines = recognizeBlocking(MlKitOcr(), blank)
        Log.i(tag, "blank lines=" + lines.size)
        assertTrue("a blank image must not invent text", lines.isEmpty())
    }

    /** The two halves of goal 2 must compose: OCR lines feed the fusion sentence. */
    @Test
    fun ocrLinesFeedTheFusionDescription() {
        val bmp = render("周六三点见")
        val lines = recognizeBlocking(MlKitOcr(), bmp)
        assertTrue("recognizer returned nothing", lines.isNotEmpty())

        val texts = lines.map {
            it.text to VisionFusion.Rect4(it.bounds.left.toFloat(), it.bounds.top.toFloat(),
                it.bounds.right.toFloat(), it.bounds.bottom.toFloat())
        }
        val described = VisionFusion.describe(emptyList(), texts)
        Log.i(tag, "fusion=[" + described + "]")
        assertTrue("fusion lost the OCR text: " + described, described.contains("图中的文字"))
    }
}
