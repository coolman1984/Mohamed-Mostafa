package com.mm.smartcgm

import android.app.Application
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Alarms

class SmartCgmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Alarms.createChannels(this)
    }
}
