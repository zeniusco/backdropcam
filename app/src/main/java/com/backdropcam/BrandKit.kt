package com.backdropcam

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A brand kit is one .zip with the logo, backdrops and brand settings, so a whole team can
 * record with the same look: export it, share it on WhatsApp / Drive / email, import it.
 */
class BrandKit(private val context: Context, private val settings: AppSettings) {

    private val filesDir = context.filesDir

    /** Writes the kit and returns the file (in the cache, shared through a FileProvider). */
    fun export(): File {
        val dir = File(context.cacheDir, "kits").apply { mkdirs() }
        val out = File(dir, "BackdropCam-brand-kit.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(KIT_JSON))
            zip.write(settings.exportBrand().toString(2).toByteArray())
            zip.closeEntry()
            val logo = File(filesDir, "logo.png")
            if (logo.exists()) addFile(zip, logo, "logo.png")
            File(filesDir, "backdrops").listFiles()?.filter { it.name.endsWith(".jpg") }?.forEach {
                addFile(zip, it, "backdrops/${it.name}")
            }
        }
        return out
    }

    private fun addFile(zip: ZipOutputStream, f: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** Reads a kit. Returns a short summary of what was imported. */
    fun import(uri: Uri): String {
        var backdrops = 0
        var logo = false
        var brand = false
        ZipInputStream(context.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Only accept the three kinds of entries a kit contains, with safe file names.
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == KIT_JSON -> {
                        settings.importBrand(JSONObject(zip.readBytes().toString(Charsets.UTF_8)))
                        brand = true
                    }
                    name == "logo.png" -> {
                        File(filesDir, "logo.png").outputStream().use { zip.copyTo(it) }
                        logo = true
                    }
                    name.startsWith("backdrops/") && SAFE_NAME.matches(name.removePrefix("backdrops/")) -> {
                        val dir = File(filesDir, "backdrops").apply { mkdirs() }
                        File(dir, name.removePrefix("backdrops/")).outputStream().use { zip.copyTo(it) }
                        backdrops++
                    }
                }
            }
        }
        if (!brand && !logo && backdrops == 0) error("This is not a BackdropCam brand kit")
        return buildList {
            if (brand) add("brand settings")
            if (logo) add("logo")
            if (backdrops > 0) add("$backdrops backdrop" + if (backdrops > 1) "s" else "")
        }.joinToString(", ")
    }

    companion object {
        private const val KIT_JSON = "brand.json"
        private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}\\.jpg")
    }
}
