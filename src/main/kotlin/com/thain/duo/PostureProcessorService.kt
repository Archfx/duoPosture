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
import vendor.surface.touchpen.V1_0.ITouchPen

public class PostureProcessorService : Service(), SensorEventListener, IHwBinder.DeathRecipient {
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var currentDisplayComposition: Int = 5
    private var currentRotation: Int = 0
    private var displayHal: IDisplayTopology? = null
    private var touchHal: ITouchPen? = null
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
        if (displayHal == null || touchHal == null) {
            connectHal()
        }
    }

    private fun connectHal() {
        try {
            displayHal = IDisplayTopology.getService(true)
            displayHal?.linkToDeath(this, DISPLAY_HAL_DEATH_COOKIE)

            touchHal = ITouchPen.getService(true)
            touchHal?.linkToDeath(this, TOUCHPEN_HAL_DEATH_COOKIE)
            Log.d(TAG, "Connected to HAL")
        } catch (e: Throwable) {
            Log.e(TAG, "HAL not connected", e)
        }
    }

    private fun setRotation(rotation: Int) {
        try {
            connectHalIfNeeded()
            if (rotation != currentRotation) {
                Log.d(TAG, "Setting display rotation ${rotation}")
                displayHal?.onRotation(rotation)
                currentRotation = rotation    
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set rotation", e)
        }
    }

    private fun setComposition(composition: Int) {
        try {
            connectHalIfNeeded()
            if (currentDisplayComposition != composition) {
                Log.d(TAG, "Setting display composition ${composition}")
                displayHal?.setComposition(composition)
                touchHal?.setDisplayState(composition)
                currentDisplayComposition = composition
            }
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
        connectHalIfNeeded()

        return Service.START_STICKY
    }

    public enum class PostureSensorValue(val value: Float) {
        Closed(0f),
        PeekRight(1f),
        PeekLeft(2f),
        Book(3f),
        Palette(4f),
        FlatDualP(5f),
        FlatDualL(6f),
        BrochureRight(7f),
        TentRight(8f),
        FlipPRight(9f),
        FlipLRight(10f),
        FlipPLeft(11f),
        FlipLLeft(12f),
        BrochureLeft(13f),
        TentLeft(14f),
        RampRight(15f),
        RampLeft(16f);

        companion object {
            infix fun from(value: Float) = PostureSensorValue.values().first { it.value == value }
        }
    }

    enum class Rotation(val value: Int) {
        R0(0),
        R90(1),
        R180(2),
        R270(3);

        companion object {
            private val map = Rotation.values().associateBy { it.value }
            infix fun from(value: Int) = map[value] ?: R0
        }
    }

    data class Posture(val posture: PostureSensorValue, val rotation: Rotation)

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
        if (displayManager == null) {
            Log.d(TAG, "Didn't get DisplayManager.")
        }
        val sensorValue = PostureSensorValue from event.values[0]
        val posture = Posture(sensorValue, Rotation from event.values[1].toInt())

        Log.d(TAG, "Got posture ${posture.posture.name} : ${posture.rotation.name}")

        setRotation(posture.rotation.value)

        windowManager?.thawRotation()

        when (posture.posture) {
            PostureSensorValue.Closed -> {
                // TODO: Turn off screen, call to power manager?
                setComposition(2)
                windowManager?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }

            PostureSensorValue.Book,
            PostureSensorValue.Palette,
            PostureSensorValue.FlatDualP,
            PostureSensorValue.FlatDualL -> {
                setComposition(2)
                windowManager?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }

            PostureSensorValue.BrochureRight, PostureSensorValue.FlipPRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)

                if (posture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 717, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -717, 0)
                }
            }

            PostureSensorValue.BrochureLeft, PostureSensorValue.FlipPLeft -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)
                
                if (posture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -717, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 717, 0)
                }
            }

            PostureSensorValue.TentRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 717)
            }

            PostureSensorValue.TentLeft ->
            {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 717)
            }

            PostureSensorValue.RampRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }
            
            PostureSensorValue.RampLeft -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
            }

            PostureSensorValue.FlipLRight -> {
                setComposition(1)
                // windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)
                if (posture.rotation == Rotation.R90) {
                    windowManager?.freezeRotation(1)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 717)
                } else {
                    windowManager?.freezeRotation(3)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, -717)
                }
            }

            PostureSensorValue.FlipLLeft -> {
                setComposition(0)
                // windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1800, 1350)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, 1350, 1800)
                if (posture.rotation == Rotation.R90) {
                    windowManager?.freezeRotation(1)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, -717)
                } else {
                    windowManager?.freezeRotation(3)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 717)
                }
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
        if ((cookie == DISPLAY_HAL_DEATH_COOKIE) || (cookie == TOUCHPEN_HAL_DEATH_COOKIE)) {
            Log.e(TAG, "HAL died!")
            // runBlocking {
                connectHal()
                setComposition(currentDisplayComposition)
            // }
        }
    }

    companion object {
        const val DISPLAY_HAL_DEATH_COOKIE: Long = 1337
        const val TOUCHPEN_HAL_DEATH_COOKIE: Long = 1338
        const val TAG = "POSTURE PROCESSOR SERVICE"
    }
}