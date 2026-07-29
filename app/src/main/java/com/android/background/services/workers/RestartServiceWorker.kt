package com.android.background.services.workers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.android.background.services.MainService

class RestartServiceWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    override fun doWork(): Result {
        val intent = Intent(applicationContext, MainService::class.java)
        try {
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}
