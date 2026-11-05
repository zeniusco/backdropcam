package com.backdropcam

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipInputStream

/** Teleprompter scripts, stored as text files inside the app. First line = title. */
class ScriptStore(private val context: Context) {

    class Script(val id: String, val title: String, val body: String)

    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }

    fun list(): List<Script> =
        (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { load(it.name) }

    fun load(id: String): Script? {
        val f = File(dir, id)
        if (!f.exists()) return null
        val text = f.readText()
        val nl = text.indexOf('\n')
        return if (nl < 0) Script(id, text.ifBlank { "Untitled" }, "")
        else Script(id, text.substring(0, nl).ifBlank { "Untitled" }, text.substring(nl + 1))
    }

    /** Saves a script; pass [id] to overwrite an existing one. Returns its id. */
    fun save(id: String?, title: String, body: String): String {
        val name = id ?: "s_${System.currentTimeMillis()}.txt"
        File(dir, name).writeText(title.replace('\n', ' ').trim().ifBlank { "Untitled" } + "\n" + body)
        return name
    }

    fun delete(id: String) {
        File(dir, id).delete()
    }

    /** Reads a .txt or .docx file chosen by the user. Returns (title, body). */
    fun import(uri: Uri): Pair<String, String> {
        val resolver = context.contentResolver
        var name = "Imported script"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val title = name.substringBeforeLast('.')
        val isDocx = name.endsWith(".docx", true) ||
            resolver.getType(uri) == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val body = if (isDocx) {
            resolver.openInputStream(uri)!!.use { readDocx(ZipInputStream(it)) }
        } else {
            resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return title to body.trim()
    }

    /** Pulls plain text out of a Word document: one line per paragraph. */
    private fun readDocx(zip: ZipInputStream): String {
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "word/document.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                val sb = StringBuilder()
                for (para in Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val p = para.value
                    for (t in Regex("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL).findAll(p)) {
                        when {
                            t.value.startsWith("<w:tab") -> sb.append(' ')
                            t.value.startsWith("<w:br") -> sb.append('\n')
                            else -> sb.append(unescape(t.groupValues[1]))
                        }
                    }
                    sb.append('\n')
                }
                return sb.toString()
            }
        }
        error("Not a Word document")
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
