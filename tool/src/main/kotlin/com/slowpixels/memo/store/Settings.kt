package com.slowpixels.memo.store

import java.io.File
import java.util.Properties

/**
 * A few remembered values in a properties file: the play speed and pitch, and where the deck stood. Written to a new
 * file and moved over the old one, so a full phone keeps the last good copy.
 */
class Settings(private val file: File) {
    private val values = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    fun int(key: String, default: Int) = values.getProperty(key)?.toIntOrNull() ?: default
    fun long(key: String, default: Long) = values.getProperty(key)?.toLongOrNull() ?: default
    fun float(key: String, default: Float) = values.getProperty(key)?.toFloatOrNull() ?: default
    fun bool(key: String, default: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: default

    fun put(vararg pairs: Pair<String, Any>) {
        var changed = false
        for ((k, v) in pairs) if (values.getProperty(k) != v.toString()) { values.setProperty(k, v.toString()); changed = true }
        if (!changed) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".new")
            tmp.outputStream().use { values.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
