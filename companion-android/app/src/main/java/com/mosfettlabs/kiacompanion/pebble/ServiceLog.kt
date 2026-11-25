package com.mosfettlabs.kiacompanion.pebble

import android.content.Context

/** Ring-buffer debug log for the Pebble listener, readable from the app UI
 *  (no adb needed). */
object ServiceLog {
    private const val PREFS = "kia_service_log"
    private const val MAX_LINES = 40

    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("lines", "") ?: ""
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "$stamp $message"
        val lines = existing.split('\n').toMutableList()
        lines.add(line)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        prefs.edit().putString("lines", lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("lines", "(no log entries yet)").orEmpty()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("lines", "(log cleared)").apply()
    }
}
