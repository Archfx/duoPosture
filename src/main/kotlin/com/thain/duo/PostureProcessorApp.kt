package com.thain.duo

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.service.quicksettings.TileService

public class PostureProcessorApp : Application() {
    public override fun onCreate() {
        super.onCreate()
        TileService.requestListeningState(this, ComponentName(this, PostureTileService::class.java))
    }
}