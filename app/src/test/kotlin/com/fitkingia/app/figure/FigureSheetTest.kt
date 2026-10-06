package com.fitkingia.app.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.fitkingia.app.ui.C
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Folhas de contato das ilustrações (início, meio e fim de cada movimento) desenhadas pela
 * ExerciseFigureView real: build/screenshots/figuras_NN.png. Só roda com -Pscreenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureSheetTest {

    @Test fun contactSheets() {
        assumeTrue(System.getProperty("screenshots") == "true")
        val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").also { it.mkdirs() }
        val ctx = RuntimeEnvironment.getApplication()
        val cellW = 420
        val cellH = 360
        val titleH = 56
        val perSheet = 6
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.text; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        val bg = Paint().apply { color = C.surface }
        MotionCatalog.all.chunked(perSheet).forEachIndexed { sheet, motions ->
            val bmp = Bitmap.createBitmap(cellW * 3, motions.size * (cellH + titleH), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(C.bg)
            motions.forEachIndexed { row, m ->
                val y0 = row * (cellH + titleH)
                canvas.drawText("${m.name}  (${m.id})", 16f, y0 + 40f, title)
                val view = ExerciseFigureView(ctx, m)
                m.samples().forEachIndexed { col, frame ->
                    canvas.save()
                    canvas.translate(col * cellW.toFloat() + 6f, y0 + titleH.toFloat())
                    canvas.drawRect(0f, 0f, cellW - 12f, cellH - 8f, bg)
                    canvas.clipRect(0f, 0f, cellW - 12f, cellH - 8f)
                    view.drawFrame(canvas, frame, cellW - 12f, cellH - 8f)
                    canvas.restore()
                }
            }
            File(out, "figuras_%02d.png".format(sheet)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
