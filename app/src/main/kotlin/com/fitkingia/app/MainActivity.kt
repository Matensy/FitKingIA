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
import com.fitkingia.app.notify.ReminderScheduler
import com.fitkingia.app.screens.HomeScreen
import com.fitkingia.app.screens.MoreScreen
import com.fitkingia.app.screens.ProgressScreen
import com.fitkingia.app.screens.QuestionnaireScreen
import com.fitkingia.app.screens.WeekScreen
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.FitKing

enum class Tab(val label: String, val icon: String) {
    HOME("Hoje", "🏠"), WEEK("Semana", "📅"), PROGRESS("Progresso", "📈"), MORE("Mais", "☰"),
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

    /** true = a tela tratou o "voltar". */
    open fun onBack(): Boolean = false
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
        root.addView(nav)
        return root
    }

    private fun renderNav(selected: Tab?) {
        nav.removeAllViews()
        nav.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected == null) return
        for (t in Tab.values()) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setGravity(Gravity.CENTER)
            item.setPadding(0, dp(8), 0, dp(10))
            item.isClickable = true
            item.contentDescription = t.label
            item.background = ripple(rounded(C.surface, 0f), 0f)
            item.setOnClickListener { if (t != selected || stack.size > 1) switchTab(t) }
            val on = t == selected
            item.addView(TextView(this).apply { text = t.icon; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); alpha = if (on) 1f else 0.55f })
            item.addView(TextView(this).apply {
                text = t.label; gravity = Gravity.CENTER
                setTextColor(if (on) C.accent else C.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                if (on) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        push(s)
    }

    fun push(s: Screen) {
        current?.let { scrollMemory[it] = scroll.scrollY; it.onLeave() }
        s.main = this
        stack.add(s)
        render(0)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).onLeave()
        render(scrollMemory.remove(current) ?: 0)
        return true
    }

    /** Volta [n] telas de uma vez (ex.: depois de concluir um fluxo). */
    fun popTo(predicate: (Screen) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.lastIndex).onLeave()
        render(scrollMemory.remove(current) ?: 0)
    }

    fun refresh(s: Screen) {
        if (s === current) render(scroll.scrollY)
    }

    /** Atualiza voltando ao topo (troca de página dentro da mesma tela). */
    fun refreshTop(s: Screen) {
        if (s === current) render(0)
    }

    private fun render(scrollY: Int) {
        val s = current ?: return
        titleView.text = s.title
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        header.setPadding(if (stack.size > 1) dp(4) else dp(16), dp(10), dp(16), dp(6))
        content.removeAllViews()
        footer.removeAllViews()
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
        }
        footer.visibility = if (footer.childCount > 0) View.VISIBLE else View.GONE
        if (footer.childCount > 0) footer.setPadding(dp(16), dp(8), dp(16), dp(4))
        renderNav(s.tab)
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun showSplash() {
        titleView.text = ""
        backButton.visibility = View.GONE
        content.removeAllViews()
        content.space(120)
        content.text("👑", 56f, gravity = Gravity.CENTER)
        content.text("FitKingIA", 28f, bold = true, gravity = Gravity.CENTER)
        content.text("Carregando o banco de conhecimento…", 14f, C.muted, gravity = Gravity.CENTER)
        renderNav(null)
    }

    private fun showFatal(e: Throwable) {
        android.util.Log.e("FitKingIA", "falha ao abrir o app", e)
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

    override fun onDestroy() {
        stack.forEach { it.onLeave() }
        super.onDestroy()
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
