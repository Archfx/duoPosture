package com.thain.duo

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

import com.thain.duo.PostureProcessorService

class PostureTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        PostureProcessorService.postureMode = PostureProcessorService.PostureMode.Automatic
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        togglePostureMode()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile
        tile?.let {
            it.state = Tile.STATE_ACTIVE
            it.label = "Active Screen"
            it.setSubtitle(getCurrentPostureModeLabel())
            it.updateTile()
        }
    }

    private fun togglePostureMode() {
        val currentMode = PostureProcessorService.postureMode
        PostureProcessorService.postureMode = when (currentMode) {
            PostureProcessorService.PostureMode.Automatic -> {
                PostureProcessorService.setManualPosture(1)
                PostureProcessorService.PostureMode.ManualLeft
            }
            PostureProcessorService.PostureMode.ManualLeft -> {
                PostureProcessorService.setManualPosture(2)
                PostureProcessorService.PostureMode.ManualRight
            }
            PostureProcessorService.PostureMode.ManualRight -> {
                PostureProcessorService.setManualPosture(0)
                PostureProcessorService.PostureMode.ManualTablet
            }
            PostureProcessorService.PostureMode.ManualTablet -> {
                PostureProcessorService.PostureMode.Automatic
            }
        }
    }

    private fun getCurrentPostureModeLabel(): String {
        return when (PostureProcessorService.postureMode) {
            PostureProcessorService.PostureMode.Automatic -> "Automatic"
            PostureProcessorService.PostureMode.ManualLeft -> "Manual(Left)"
            PostureProcessorService.PostureMode.ManualRight -> "Manual(Right)"
            PostureProcessorService.PostureMode.ManualTablet -> "Manual(Tablet)"
        }
    }

    companion object {
        const val TAG = "PostureTileService"
    }
}