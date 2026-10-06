package com.fitkingia.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.knowledge.KnowledgeReader
import java.io.File

/**
 * Monta o app: fitness.db (conhecimento, só leitura, vem nos assets) + user.db (dados pessoais,
 * só no aparelho). Os dois arquivos ficam separados: atualizar o conhecimento nunca toca o user.db.
 */
object Graph {
    @Volatile var fit: FitKing? = null

    /** Testes trocam o carregamento por um banco JDBC; no app real é null. */
    @Volatile var override: ((Context) -> FitKing)? = null

    /** Sincronizado: a tela e um lembrete (BroadcastReceiver) podem abrir o app ao mesmo tempo. */
    @Synchronized
    fun load(context: Context): FitKing {
        fit?.let { return it }
        override?.let { return it(context).also { f -> fit = f } }
        val kb = loadKnowledge(context)
        val userDb = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("user.db").also { it.parentFile?.mkdirs() }, null)
        userDb.setForeignKeyConstraintsEnabled(true) // "apagar meus dados" depende do ON DELETE CASCADE
        val sql = AndroidSqlDatabase(userDb)
        UserDb.migrate(sql, context.assets.open("user.sql").bufferedReader().use { it.readText() })
        return FitKing(kb, sql).also { fit = it }
    }

    private fun loadKnowledge(context: Context): KnowledgeBase {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val dir = context.getDatabasePath("x").parentFile!!.also { it.mkdirs() }
        // Um arquivo por versão instalada do app: atualizar o APK traz o conhecimento novo.
        val name = "fitness-${info.lastUpdateTime}.db"
        val target = File(dir, name)
        if (!target.exists()) {
            dir.listFiles { f -> f.name.startsWith("fitness-") }?.forEach { it.delete() }
            val tmp = File(dir, "$name.tmp")
            context.assets.open("fitness.db").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "não foi possível preparar o banco de conhecimento" }
        }
        val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return KnowledgeReader(AndroidSqlDatabase(db)).load()
        } finally {
            db.close()
        }
    }
}
