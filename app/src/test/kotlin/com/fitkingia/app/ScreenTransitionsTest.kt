package com.fitkingia.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitkingia.app.ui.C
import com.fitkingia.app.ui.Motion
import com.fitkingia.app.ui.ScreenChange
import com.fitkingia.app.ui.ScreenTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.ref.WeakReference

/** Foto da transição (o que aparece saindo) e memória do Motion entre telas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTransitionsTest {
    @Before fun setUp() {
        Motion.enabled = false // a foto não depende do interruptor; nada aqui precisa animar
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Área de conteúdo rolável com faixas de cores diferentes (cada uma 100 px). */
    private fun scrolledArea(activity: Activity): Pair<FrameLayout, ScrollView> {
        val host = FrameLayout(activity)
        val scroll = ScrollView(activity)
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        for (i in 0 until 40) {
            val v = View(activity)
            v.setBackgroundColor(Color.rgb((i * 53) % 256, (i * 97) % 256, (i * 31) % 256))
            content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100))
        }
        scroll.addView(content)
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(host)
        idle()
        return host to scroll
    }

    /** O que está visível na área agora (desenhado como o pai desenharia: com a rolagem). */
    private fun visible(area: View, scrolled: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(C.bg)
        if (scrolled) c.translate(-area.scrollX.toFloat(), -area.scrollY.toFloat())
        area.draw(c)
        return bmp
    }

    @Test fun captureShowsTheScrolledPartNotTheTop() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (host, scroll) = scrolledArea(activity)
        scroll.scrollTo(0, 1550)
        idle()
        assertTrue("a área precisa rolar para o teste valer", scroll.scrollY > 1000)
        val transitions = ScreenTransitions(host, scroll)
        transitions.capture()
        val shot = transitions.lastCapture
        assertNotNull(shot)
        assertTrue("a foto deve ser a parte visível (rolada)", shot!!.sameAs(visible(scroll, scrolled = true)))
        assertFalse("e não o topo do conteúdo", shot.sameAs(visible(scroll, scrolled = false)))

        transitions.release()
        assertNull("release solta a foto do tamanho da tela", transitions.lastCapture)
    }

    /**
     * App minimizado (onTrimMemory) logo depois de trocar de tela: soltar a foto não pode congelar a
     * entrada no meio (conteúdo transparente ou deslocado até o próximo toque).
     */
    @Test fun releaseDuringTheTransitionLeavesTheNewScreenInPlace() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (host, scroll) = scrolledArea(activity)
        val content = scroll.getChildAt(0) as ViewGroup
        val title = View(activity)
        val footer = View(activity)
        val transitions = ScreenTransitions(host, scroll)
        transitions.capture()
        transitions.play(ScreenChange.POP, content, title, footer) // entra com fade e deslize
        assertTrue("o teste precisa pegar a entrada em andamento", content.alpha < 1f || content.translationX != 0f)
        transitions.release()
        for (v in listOf(content, title, footer)) {
            assertEquals(1f, v.alpha, 0f)
            assertEquals(0f, v.translationX, 0f)
        }
        assertNull(transitions.lastCapture)
    }

    /** O Motion vive o processo inteiro: não pode segurar a tela (e com ela a Activity) depois do render. */
    @Test fun motionDoesNotRetainTheLastScreen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var screen: Any? = Any()
        val ref = WeakReference(screen)
        Motion.beginRender(activity, screen!!, ScreenChange.ROOT)
        Motion.endRender()
        @Suppress("UNUSED_VALUE")
        screen = null
        var tries = 0
        while (ref.get() != null && tries++ < 20) {
            System.gc()
            Thread.sleep(20)
        }
        assertNull("Motion ainda segura a última tela", ref.get())
    }
}
