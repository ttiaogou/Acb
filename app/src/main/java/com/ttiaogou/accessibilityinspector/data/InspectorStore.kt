package com.ttiaogou.accessibilityinspector.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

object InspectorStore {
    private const val PREFS_NAME = "accessibility_inspector_store"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val REPORT_FILE_NAME = "last_accessibility_report.json"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun reportFile(context: Context): AtomicFile {
        val baseFile = File(context.applicationContext.filesDir, REPORT_FILE_NAME)
        return AtomicFile(baseFile)
    }

    fun isMonitoring(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MONITORING, false)

    fun setMonitoring(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    /**
     * 原子保存完整 JSON 报告。
     * 成功返回 true；失败时回滚临时写入并返回 false。
     */
    fun saveReport(context: Context, json: String): Boolean {
        if (json.isBlank()) return false

        val atomicFile = reportFile(context)
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomicFile.startWrite()
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
            true
        } catch (_: Exception) {
            if (output != null) {
                runCatching { atomicFile.failWrite(output) }
            }
            false
        }
    }

    fun getLastReport(context: Context): String? =
        runCatching {
            reportFile(context).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clearReport(context: Context) {
        runCatching { reportFile(context).delete() }
    }
}
