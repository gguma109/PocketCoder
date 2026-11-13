package com.pocketcoder.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/** Hands a verified APK to Android's package installer. Android shows its own confirmation screen. */
internal object UpdateInstaller {
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("pocketcoder.apk", 0, apk.length()).use { output ->
                    input.copyTo(output); session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // The installer fills in status extras, so the PendingIntent must be mutable on Android 12+.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The app is replaced and restarted by the system.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "업데이트 설치를 취소했습니다", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(context, "업데이트 설치 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"), Toast.LENGTH_LONG).show()
        }
    }
}
