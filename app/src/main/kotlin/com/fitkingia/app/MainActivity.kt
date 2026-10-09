package com.fitkingia.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fitkingia.app.data.Graph
import com.fitkingia.app.notify.Notifier
import com.fitkingia.app.notify.ReminderScheduler
import com.fitkingia.app.screens.HomeScreen
import com.fitkingia.app.screens.MoreScreen
import com.fitkingia.app.screens.ProgressScreen
import com.fitkingia.app.screens.QuestionnaireScreen
import com.fitkingia.app.screens.WeekScreen
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.FitKing

/** Aba da barra inferior. [icon] null = ícone desenhado ([CalendarIcon]). */
enum class Tab(val label: String, val icon: String?) {
    // Semana sem emoji: 📅 e 🗓 aparecem na fonte do Android com o texto "July 17" (inglês, data fixa).
    HOME("Hoje", "🏠"), WEEK("Semana", null), PROGRESS("Progresso", "📈"), MORE("Mais", "☰"),
}

/** Uma tela do app: constrói sua UI em código a cada atualização (estado vem do user.db). */
abstract class Screen {
    lateinit var main: MainActivity
    abstract val title: String
    /** Aba destacada na barra inferior; null esconde a barra (questionário, treino). */
    open val tab: Tab? = null
    open val keepScreenOn: Boolean = false
    val fit: FitKing get() = main.fit

    abstract fun build(root: LinearLayout)

    /** Área fixa acima da barra inferior (ex.: cronômetro de descanso). */
    open fun footer(root: LinearLayout) {}

    /**
     * Página atual dentro da tela (ex.: pergunta do questionário). Em refreshTop(), página maior
     * desliza da direita e menor, da esquerda.
     */
    open val page: Int get() = 0

    /** true = a tela tratou o "voltar". */
    open fun onBack(): Boolean = false
    /**
     * A tela saiu da pilha de vez (voltar, nova raiz ou Activity destruída). Coberta por outra
     * tela ela continua viva (ex.: o descanso do treino segue contando enquanto se lê "Como fazer").
     */
    open fun onLeave() {}

    fun refresh() = main.refresh(this)
    fun push(s: Screen) = main.push(s)
    fun pop() = main.pop()
}

class MainActivity : Activity() {
    lateinit var fit: FitKing
        private set

    private val stack = ArrayList<Screen>()
    private val scrollMemory = HashMap<Screen, Int>()
    private lateinit var header: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var titleView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var nav: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var pickerCallback: ((Uri) -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private lateinit var transitions: ScreenTransitions
    private var splashAnims: List<android.animation.Animator> = emptyList()
    private var lastPage = 0
    private val navItems = ArrayList<NavItem>()
    private var navSelected: Tab? = null

    private class NavItem(val tab: Tab, val view: View, val pill: View, val icon: View, val label: TextView) { var on = false }

    val current: Screen? get() = stack.lastOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.statusBarColor = C.bg
        window.navigationBarColor = C.surface
        setContentView(buildShell())
        showSplash()
        background({ Graph.load(applicationContext) }) { r ->
            r.onSuccess { f ->
                fit = f
                // Alarmes somem se o app for forçado a parar: abrir o app deixa os lembretes em dia.
                runCatching { ReminderScheduler.schedule(applicationContext, fit) }
                tidyReminders()
                if (fit.hasProfile()) setRoot(HomeScreen()) else setRoot(QuestionnaireScreen(fit.currentAnswers(), firstRun = true))
            }.onFailure { e -> showFatal(e) }
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.bg)

        header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(8), dp(10), dp(16), dp(6))
        backButton = TextView(this).apply {
            text = "←"
            setTextColor(C.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            contentDescription = "Voltar"
            setOnClickListener { onBackPressed() }
        }
        header.addView(backButton)
        titleView = TextView(this).apply {
            setTextColor(C.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(4), dp(16), dp(24))
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = LinearLayout(this)
        footer.orientation = LinearLayout.VERTICAL
        footer.setPadding(dp(16), 0, dp(16), 0)
        root.addView(footer)

        nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(C.surface)
        nav.visibility = View.GONE
        root.addView(nav)
        transitions = ScreenTransitions(root, scroll)
        return root
    }

    /** Barra inferior: montada uma vez; a aba selecionada ganha uma "pílula" atrás do ícone. */
    private fun buildNav() {
        for (t in Tab.values()) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setGravity(Gravity.CENTER)
            item.setPadding(0, dp(6), 0, dp(8))
            item.isClickable = true
            item.contentDescription = t.label
            item.background = ripple(rounded(C.surface, 0f), 0f)
            item.setOnClickListener { if (t != navSelected || stack.size > 1) switchTab(t) }
            val box = FrameLayout(this)
            val pill = View(this)
            pill.background = rounded(C.accentDark, dp(16).toFloat())
            pill.alpha = 0f
            box.addView(pill, FrameLayout.LayoutParams(dp(56), dp(30), Gravity.CENTER))
            val emoji = t.icon
            val icon: View = if (emoji != null) TextView(this).apply { text = emoji; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
            else View(this).apply { background = CalendarIcon() }
            icon.alpha = 0.55f
            box.addView(icon, if (emoji != null) FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                else FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            item.addView(box, LinearLayout.LayoutParams(dp(56), dp(30)))
            val label = TextView(this).apply {
                text = t.label; gravity = Gravity.CENTER
                setTextColor(C.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            navItems.add(NavItem(t, item, pill, icon, label))
        }
    }

    private fun renderNav(selected: Tab?) {
        val wasVisible = nav.visibility == View.VISIBLE
        nav.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected == null) return
        if (navItems.isEmpty()) buildNav()
        val animate = Motion.on(this)
        if (!wasVisible && animate) {
            // A barra sobe ao reaparecer (ex.: ao sair do questionário ou do treino).
            val h = dp(60).toFloat()
            nav.translationY = h
            android.animation.ObjectAnimator.ofFloat(nav, View.TRANSLATION_Y, h, 0f).apply { duration = 280; interpolator = Motion.easeOut; start() }
        }
        if (selected == navSelected) return
        val animateTab = animate && wasVisible && navSelected != null
        navSelected = selected
        for (item in navItems) styleNavItem(item, item.tab == selected, animateTab)
    }

    private fun styleNavItem(item: NavItem, on: Boolean, animate: Boolean) {
        val wasOn = item.on
        item.on = on
        item.view.isSelected = on // leitor de tela anuncia a aba ativa
        item.label.setTextColor(if (on) C.accent else C.muted)
        item.label.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
        item.pill.animate().cancel()
        item.icon.animate().cancel()
        if (!animate || on == wasOn) {
            item.pill.alpha = if (on) 1f else 0f
            item.pill.scaleX = 1f
            item.icon.alpha = if (on) 1f else 0.55f
            return
        }
        if (on) {
            // A pílula se abre a partir do centro e o ícone dá um pequeno "pop".
            item.pill.scaleX = 0.4f
            item.pill.animate().alpha(1f).scaleX(1f).setDuration(260).setInterpolator(Motion.easeOut).start()
            item.icon.alpha = 1f
            Motion.pop(item.icon, from = 0.8f)
        } else {
            item.pill.animate().alpha(0f).scaleX(0.7f).setDuration(160).setInterpolator(Motion.easeIn).start()
            item.icon.animate().alpha(0.55f).setDuration(160).start()
        }
    }

    fun tabScreen(t: Tab): Screen = when (t) {
        Tab.HOME -> HomeScreen()
        Tab.WEEK -> WeekScreen()
        Tab.PROGRESS -> ProgressScreen()
        Tab.MORE -> MoreScreen()
    }

    fun switchTab(t: Tab) = setRoot(tabScreen(t))

    fun setRoot(s: Screen) {
        stack.forEach { it.onLeave() }
        stack.clear()
        scrollMemory.clear()
        s.main = this
        stack.add(s)
        render(0, ScreenChange.ROOT)
    }

    fun push(s: Screen) {
        // A tela coberta não recebe onLeave: ela volta no pop (e o que ela agendou continua valendo).
        current?.let { scrollMemory[it] = scroll.scrollY }
        s.main = this
        stack.add(s)
        render(0, if (stack.size > 1) ScreenChange.PUSH else ScreenChange.ROOT)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).onLeave()
        render(scrollMemory.remove(current) ?: 0, ScreenChange.POP)
        return true
    }

    /** Volta [n] telas de uma vez (ex.: depois de concluir um fluxo). */
    fun popTo(predicate: (Screen) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.lastIndex).onLeave()
        render(scrollMemory.remove(current) ?: 0, ScreenChange.POP)
    }

    /** Atualiza a tela atual sem animar a troca (só o retorno do toque e valores que mudaram). */
    fun refresh(s: Screen) {
        if (s === current) render(scroll.scrollY, ScreenChange.NONE)
    }

    /** Atualiza voltando ao topo (troca de página dentro da mesma tela), deslizando na direção da página. */
    fun refreshTop(s: Screen) {
        if (s !== current) return
        val p = s.page
        render(0, when {
            p > lastPage -> ScreenChange.FORWARD
            p < lastPage -> ScreenChange.BACK
            else -> ScreenChange.FADE
        })
    }

    private fun render(scrollY: Int, change: ScreenChange) {
        val s = current ?: return
        val animate = change != ScreenChange.NONE && Motion.on(this)
        splashAnims.forEach { it.cancel() }
        splashAnims = emptyList()
        if (animate) transitions.capture()
        val tap = Motion.captureTap(content, footer) // sempre consome o toque; só o refresh o repete
        titleView.text = s.title
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        header.setPadding(if (stack.size > 1) dp(4) else dp(16), dp(10), dp(16), dp(6))
        content.removeAllViews()
        footer.removeAllViews()
        Motion.beginRender(this, s, change)
        try {
            s.build(content)
            s.footer(footer)
        } catch (e: Exception) {
            android.util.Log.e("FitKingIA", "falha ao montar ${s.javaClass.simpleName}", e)
            content.removeAllViews()
            content.card(color = C.surface, stroke = C.danger) {
                h3("Algo deu errado nesta tela")
                muted(e.toString())
                button("Voltar ao início", Btn.SECONDARY) { setRoot(HomeScreen()) }
            }
        } finally {
            Motion.endRender()
        }
        lastPage = s.page
        footer.visibility = if (footer.childCount > 0) View.VISIBLE else View.GONE
        if (footer.childCount > 0) footer.setPadding(dp(16), dp(8), dp(16), dp(4))
        renderNav(s.tab)
        if (animate) transitions.play(change, content, titleView, footer)
        else if (change == ScreenChange.NONE) Motion.replayTap(tap, content, footer)
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun showSplash() {
        titleView.text = ""
        backButton.visibility = View.GONE
        content.removeAllViews()
        content.space(120)
        val crown = content.text("👑", 56f, gravity = Gravity.CENTER)
        val name = content.text("FitKingIA", 28f, bold = true, gravity = Gravity.CENTER)
        val hint = content.text("Carregando o banco de conhecimento…", 14f, C.muted, gravity = Gravity.CENTER)
        renderNav(null)
        splashAnims = Motion.splash(crown, name, hint)
    }

    private fun showFatal(e: Throwable) {
        android.util.Log.e("FitKingIA", "falha ao abrir o app", e)
        splashAnims.forEach { it.cancel() }
        splashAnims = emptyList()
        content.removeAllViews()
        content.space(60)
        content.card(stroke = C.danger) {
            h3("Não foi possível abrir o app")
            muted(e.toString())
        }
    }

    override fun onBackPressed() {
        val s = current
        if (s != null && s.onBack()) return
        if (pop()) return
        if (s != null && s.tab != null && s.tab != Tab.HOME) { switchTab(Tab.HOME); return }
        super.onBackPressed()
    }

    /**
     * Voltando de outro app (ex.: configurações de notificação, onde a pessoa liberou a permissão ou
     * religou os lembretes): a tela atual é remontada para refletir o estado real do Android. onRestart
     * e não onResume: o diálogo de permissão só pausa a Activity, e o primeiro resume vem do onCreate.
     */
    override fun onRestart() {
        super.onRestart()
        if (!::fit.isInitialized) return
        tidyReminders()
        current?.let { refresh(it) }
    }

    /** Saindo do app: tira da barra os lembretes resolvidos aqui dentro (treino concluído, água em dia). */
    override fun onStop() {
        if (::fit.isInitialized) tidyReminders()
        super.onStop()
    }

    private fun tidyReminders() {
        runCatching { Notifier.withdrawStale(applicationContext, fit) }
    }

    override fun onDestroy() {
        splashAnims.forEach { it.cancel() }
        stack.forEach { it.onLeave() }
        stack.clear()
        transitions.release()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // App fora da tela: a foto da transição (do tamanho da tela) é refeita na próxima navegação.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::transitions.isInitialized) transitions.release()
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades para as telas
    // ---------------------------------------------------------------------------------------

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Trabalho pesado (gerar programa, simular) fora da thread de UI. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        if (synchronous) { done(runCatching(work)); return }
        Thread {
            val r = runCatching(work)
            handler.post { if (!isFinishing) done(r) }
        }.start()
    }

    fun confirm(title: String, message: String, confirmLabel: String, danger: Boolean = false, onConfirm: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Diálogo com conteúdo construído pelas mesmas funções das telas. */
    fun sheet(title: String, build: LinearLayout.(close: () -> Unit) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val sv = ScrollView(this)
        sv.addView(box)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(sv).create()
        box.build { dialog.dismiss() }
        dialog.show()
    }

    fun pickImage(callback: (Uri) -> Unit) {
        pickerCallback = callback
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, "Escolher foto"), REQ_IMAGE)
        } catch (e: Exception) {
            toast("Nenhum app de galeria disponível")
        }
    }

    /** Salvar arquivo onde o usuário escolher (Storage Access Framework, sem permissões). */
    fun createDocument(fileName: String, mime: String, callback: (Uri) -> Unit) {
        pickerCallback = callback
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, fileName)
        try {
            startActivityForResult(intent, REQ_DOCUMENT)
        } catch (e: Exception) {
            toast("Nenhum app de arquivos disponível")
        }
    }

    @Deprecated("API antiga, suficiente para o seletor de imagens e de arquivos")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQ_IMAGE || requestCode == REQ_DOCUMENT) && resultCode == RESULT_OK) data?.data?.let { uri -> pickerCallback?.invoke(uri) }
        pickerCallback = null
    }

    /** Pede uma permissão em tempo de execução; [done] recebe true se ela foi (ou já estava) concedida. */
    fun requestPermission(permission: String, done: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { done(true); return }
        permissionCallback = done
        requestPermissions(arrayOf(permission), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        val callback = permissionCallback
        permissionCallback = null
        callback?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    fun postDelayed(ms: Long, r: () -> Unit) = handler.postDelayed(r, ms)
    fun removeCallbacks(r: Runnable) = handler.removeCallbacks(r)
    val mainHandler: Handler get() = handler

    companion object {
        private const val REQ_IMAGE = 41
        private const val REQ_DOCUMENT = 42
        private const val REQ_PERMISSION = 43

        /** Testes rodam o trabalho de fundo na mesma thread. */
        @Volatile var synchronous = false
    }
}
