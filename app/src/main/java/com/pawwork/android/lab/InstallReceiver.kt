package com.pawwork.android.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives PackageInstaller commit results (broadcast path — no Activity/window
 * needed, so SystemUI ANRs cannot stall the install outcome).
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile var lastStatus: Int = -1
        @Volatile var lastMessage: String = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        lastStatus = status
        lastMessage = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "waiting for user action"
            PackageInstaller.STATUS_SUCCESS -> "success"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted by user"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
            else -> "status=$status"
        }
        // persist for run-as inspection
        try {
            val f = java.io.File(context.filesDir, "install_result.log")
            f.appendText("${System.currentTimeMillis()} session=$sessionId status=$sessionId -> $lastMessage\n")
        } catch (_: Exception) {}
    }
}