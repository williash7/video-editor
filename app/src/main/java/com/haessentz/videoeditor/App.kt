package com.haessentz.videoeditor

import android.app.Application
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.work.WorkService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ProjectStore.init(this)
        WorkService.ensureChannel(this)
    }
}
