package com.airferry.sender

import android.app.Application
import com.airferry.sender.share.ShareIntake

class AirFerrySenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShareIntake.purgeStale(this)
    }
}
