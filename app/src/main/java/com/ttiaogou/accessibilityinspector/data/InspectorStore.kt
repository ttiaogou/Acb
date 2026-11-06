package com.ttiaogou.accessibilityinspector.data

import android.content.Context
import java.io.File

object InspectorStore {
    private const val PREFS_NAME = "accessibility_inspector_store"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val REPORT_FILE_NAME = "last_accessibility_report.json"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMonitoring(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MONITORING, false)

    fun setMonitoring(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    fun saveReport(context: Context, json: String) {
        val target = File(context.applicationContext.filesDir, REPORT_FILE_NAME)
        val temporary = File(context.applicationContext.filesDir, "$REPORT_FILE_NAME.tmp")
        runCatching {
            temporary.writeText(json, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    fun getLastReport(context: Context): String? {
        val report = File(context.applicationContext.filesDir, REPORT_FILE_NAME)
        return runCatching { if (report.exists()) report.readText(Charsets.UTF_8) else null }
            .getOrNull()
    }

    fun clearReport(context: Context) {
        runCatching { File(context.applicationContext.filesDir, REPORT_FILE_NAME).delete() }
    }
}
