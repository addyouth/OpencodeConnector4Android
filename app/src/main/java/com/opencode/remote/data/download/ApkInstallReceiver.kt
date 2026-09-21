package com.opencode.remote.data.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 手机端应用内更新：下载完成自动调起安装（未知来源先引导去开权限）。
 * 注册在 Application（进程常驻：SSE 前台服务保活），下载中杀进程则回退为点系统通知手动装。
 */
object UpdateDownloadTracker {
    @Volatile var lastDownloadId: Long = -1L
    @Volatile var lastFileName: String = ""
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L || id != UpdateDownloadTracker.lastDownloadId) return
        val fileName = UpdateDownloadTracker.lastFileName.ifEmpty { return }
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.w(TAG, "Download not successful: status=$status")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download query failed", e)
            return
        }
        UpdateDownloadTracker.lastDownloadId = -1L
        installApk(context, fileName)
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"

        fun installApk(context: Context, fileName: String) {
            try {
                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    Toast.makeText(context, "请允许安装未知应用后重试", Toast.LENGTH_LONG).show()
                    val settings = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settings)
                    return
                }
                val apkFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                if (!apkFile.exists()) {
                    Toast.makeText(context, "安装包不存在，请重新下载", Toast.LENGTH_LONG).show()
                    return
                }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apkFile
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(install)
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                try {
                    Toast.makeText(context, "调起安装失败：${e.message}", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }
}
