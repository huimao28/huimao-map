package com.huimao.map.navigation

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时导航探针日志：把百度导航回调逐项记录到文件，用于验证车道、路口放大图、
 * 高速信息等数据在诱导过程中的可用性。验证完成后与 NaviActivity 中的探针代码一并删除。
 */
object NaviProbe {
    private const val LOG_TAG = "NaviProbe"

    @Volatile var active = false
        private set

    private var logFile: File? = null
    private var startedAt = 0L
    private val lock = Any()

    fun start(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "navi_probe").apply { mkdirs() }
        val name = "probe_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        logFile = File(dir, name)
        startedAt = SystemClock.elapsedRealtime()
        active = true
        log("PROBE", "started file=${logFile?.absolutePath}")
    }

    fun stop() {
        if (active) log("PROBE", "stopped")
        active = false
    }

    fun log(event: String, detail: String) {
        val elapsed = if (startedAt > 0L) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else 0.0
        val line = String.format(Locale.US, "[%8.1fs] %-10s %s", elapsed, event, detail)
        Log.i(LOG_TAG, line)
        val file = logFile ?: return
        synchronized(lock) {
            runCatching { file.appendText(line + "\n") }
        }
    }

    /** 把当前探针日志复制到公共「下载」目录，方便从文件管理器取出。返回结果说明。 */
    fun exportToDownloads(context: Context): String {
        val file = logFile ?: return "还没有探针日志（先点一次开始模拟）"
        if (!file.exists() || file.length() == 0L) return "探针日志为空"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "导出失败：无法在下载目录创建文件"
                val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    true
                } ?: false
                if (ok) "已导出到「下载」文件夹：${file.name}" else "导出失败：无法写入下载文件"
            } else {
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    file.name
                )
                file.copyTo(dest, overwrite = true)
                "已导出到「下载」文件夹：${file.name}"
            }
        } catch (e: Throwable) {
            "导出失败：${e.message}"
        }
    }
}
