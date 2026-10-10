package com.haessentz.videoeditor.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

object ProjectStore {
    private lateinit var root: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "projects").apply { mkdirs() }
        _projects.value = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
            runCatching { json.decodeFromString<Project>(File(d, "project.json").readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    @Synchronized
    fun put(p: Project, save: Boolean = true) {
        _projects.value = (_projects.value.filter { it.id != p.id } + p).sortedByDescending { it.createdAt }
        if (save) write(p)
    }

    @Synchronized
    fun update(id: String, save: Boolean = true, f: (Project) -> Project): Project? {
        val cur = get(id) ?: return null
        val n = f(cur)
        put(n, save)
        return n
    }

    @Synchronized
    fun delete(id: String) {
        File(root, id).deleteRecursively()
        _projects.value = _projects.value.filter { it.id != id }
    }

    private fun write(p: Project) {
        val d = dir(p.id)
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), p))
        tmp.renameTo(File(d, "project.json"))
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    var activeModel: String
        get() = sp.getString("model", "ivrit-turbo") ?: "ivrit-turbo"
        set(v) = sp.edit().putString("model", v).apply()

    /** The transcription model chosen for a language. */
    fun modelFor(lang: String): String = if (lang == "ru") sp.getString("model_ru", "turbo-q5") ?: "turbo-q5" else activeModel
    fun setModelFor(lang: String, id: String) {
        if (lang == "ru") sp.edit().putString("model_ru", id).apply() else activeModel = id
    }

    /** The Google account (YouTube channel) uploads go to, per language. */
    fun accountFor(lang: String): String =
        sp.getString("acct_$lang", null) ?: if (lang == "ru") "chabadbaaliya.afula@gmail.com" else "haessentz@gmail.com"
    fun setAccountFor(lang: String, email: String) = sp.edit().putString("acct_$lang", email.trim()).apply()

    var lastLanguage: String
        get() = sp.getString("lastLang", "he") ?: "he"
        set(v) = sp.edit().putString("lastLang", v).apply()

    var aiPrompt: String
        get() = sp.getString("aiPrompt", null) ?: AiImport.DEFAULT_PROMPT
        set(v) = sp.edit().putString("aiPrompt", v).apply()

    fun aiPromptFor(lang: String): String =
        if (lang == "ru") sp.getString("aiPromptRu", null) ?: AiImport.DEFAULT_PROMPT_RU else aiPrompt
    fun setAiPromptFor(lang: String, v: String?) {
        val key = if (lang == "ru") "aiPromptRu" else "aiPrompt"
        if (v == null) sp.edit().remove(key).apply() else sp.edit().putString(key, v).apply()
    }

    val defaultThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    var threads: Int
        get() = sp.getInt("threads", defaultThreads)
        set(v) = sp.edit().putInt("threads", v).apply()
}
