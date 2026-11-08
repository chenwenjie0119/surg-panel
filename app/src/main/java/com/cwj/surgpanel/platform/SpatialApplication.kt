package com.cwj.surgpanel.platform

import android.app.Application
import com.cwj.surgpanel.mainApp
import com.pico.spatial.ui.foundation.dsl.launch

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
        SessionClock.start()
    }
}
