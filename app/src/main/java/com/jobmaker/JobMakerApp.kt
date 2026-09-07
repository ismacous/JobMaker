package com.jobmaker

import android.app.Application
import com.jobmaker.di.AppContainer

class JobMakerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
