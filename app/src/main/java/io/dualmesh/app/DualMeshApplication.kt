package io.dualmesh.app

import android.app.Application
import io.dualmesh.app.car.AndroidAutoMessaging
import org.maplibre.android.MapLibre

class DualMeshApplication : Application() {
    val manager by lazy { DualMeshManager(applicationContext) }

    lateinit var autoMessaging: AndroidAutoMessaging
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        autoMessaging = AndroidAutoMessaging(this, manager)
        autoMessaging.start()
        manager.startAutoReconnect()
    }

    override fun onTerminate() {
        autoMessaging.close()
        manager.close()
        super.onTerminate()
    }
}
