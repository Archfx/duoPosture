package com.thain.duo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.IHwBinder
import android.os.HwRemoteBinder
import android.os.RemoteException
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.WindowManagerGlobal
import android.util.Log

import kotlinx.coroutines.runBlocking

import vendor.surface.displaytopology.V1_0.IDisplayTopology

public class PostureProcessorService : Service(), SensorEventListener, IHwBinder.DeathRecipient {
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var currentDisplayComposition: Int = 5
    private var hal: IDisplayTopology? = null
    private var windowManager: IWindowManager? = null
    private var displayManager: IDisplayManager? = null


    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager!!.getSensorList(Sensor.TYPE_ALL).stream().filter { s -> 
            s.getStringType().contains("microsoft.sensor.posture")
        }.findFirst().orElse(null)
        sensor?.let {
            sensorManager!!.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        windowManager = WindowManagerGlobal.getWindowManagerService()
        if (windowManager == null) {
            Log.e(TAG, "Cannot get Window Manager")
        }

        displayManager = DisplayManagerGlobal.getDisplayManagerService();
        if (displayManager == null) {
            Log.e(TAG, "Cannot get Display Manager")
        }

        connectHal()
    }

    private fun connectHalIfNeeded() {
        if (hal == null) {
            connectHal()
        }
    }

    private fun connectHal() {
        try {
            hal = IDisplayTopology.getService(true)
            hal?.linkToDeath(this, HAL_DEATH_COOKIE)
            Log.d(TAG, "Connected to HAL")
        } catch (e: Throwable) {
            Log.e(TAG, "HAL not connected", e)
        }
    }

    private fun setComposition(composition: Int) {
        try {
            connectHalIfNeeded()

            Log.d(TAG, "Setting display composition ${composition}")
            hal?.setComposition(composition)
            currentDisplayComposition = composition
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set composition", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensor?.let {
            sensorManager!!.unregisterListener(this, sensor)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (hal == null) {
            connectHal()
        }

        return Service.START_STICKY
    }

    /**
     *  SensorEventListener
     */

    // TODO: Need to parse posture rotation as well to set proper display offset
    // event.values[0]: Posture
    // event.values[1]: Rotation
    // V2 exclusive, query for version first (sensor.getVersion(), 1 or 2)
    // event.values[2]: Gesture
    // event.values[3]: confidence
    // event.values[4]: accelY
    // event.values[5]: hingeAngle

    override fun onSensorChanged(event: SensorEvent) {
        Log.d(TAG, "Got sensor event ${event.values[0]}")
        if (displayManager == null) {
            Log.d(TAG, "Didn't get DisplayManager.")
        }

        when (event.values[0]) {
            // Span
            0f, 1f, 2f, 3f, 4f, 5f, 6f -> {
                setComposition(2)
                windowManager?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }
            // Right Portrait
            7f, 9f -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 717, 0)
            }

            // Right Landscape
            8f, 10f, 15f -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }

            // Left Portrait
            11f, 13f -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -717, 0)
            }

            // Left Landscape
            12f, 14f, 16f -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            } 
            else -> {
                setComposition(2)
                windowManager?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

    }

    /**
     * HwBinder.DeathRecipient
     */

    override fun serviceDied(cookie: Long) {
        if (cookie == HAL_DEATH_COOKIE) {
            Log.e(TAG, "HAL died!")
            // runBlocking {
                connectHal()
                setComposition(currentDisplayComposition)
            // }
        }
    }

    companion object {
        const val HAL_DEATH_COOKIE: Long = 1337
        const val TAG = "POSTURE PROCESSOR SERVICE"
    }
}