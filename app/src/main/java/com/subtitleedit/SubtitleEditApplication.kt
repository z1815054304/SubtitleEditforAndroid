package com.subtitleedit

import android.app.Application
import com.subtitleedit.util.RuntimeLogManager

class SubtitleEditApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeLogManager.install(this)
    }
}
