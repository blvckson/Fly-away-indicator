package com.blvckson.flyawayindicator

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ScreenMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}