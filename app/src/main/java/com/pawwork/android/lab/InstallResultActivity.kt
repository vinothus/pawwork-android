package com.pawwork.android.lab

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.Toast

/** Shows the outcome of a PackageInstaller commit (consent dialog result). */
class InstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Waiting for your decision…"
            PackageInstaller.STATUS_SUCCESS -> "✅ Installed successfully!"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "❌ Install rejected by user"
            PackageInstaller.STATUS_FAILURE_INVALID -> "❌ Invalid APK"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "❌ Incompatible APK"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "❌ Package conflict (different signature?)"
            else -> "Install status: $status"
        }
        Toast.makeText(this, "PawWork: $msg", Toast.LENGTH_LONG).show()
        finish()
    }
}