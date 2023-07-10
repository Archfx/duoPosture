package com.thain.duo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.devicestate.DeviceStateManagerGlobal;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.os.IBinder
import android.os.IHwBinder
import android.os.HwRemoteBinder
import android.os.RemoteException
import android.os.PowerManager
import android.os.SystemClock
import android.os.SystemProperties
import android.os.UserHandle
import android.view.Display.DEFAULT_DISPLAY
import android.view.IRotationWatcher
import android.view.IWindowManager
import android.view.WindowManagerGlobal
import android.util.Log

import kotlinx.coroutines.runBlocking

import vendor.surface.displaytopology.V1_0.IDisplayTopology
import vendor.surface.touchpen.V1_0.ITouchPen

import com.thain.duo.ResourceHelper.WIDTH
import com.thain.duo.ResourceHelper.HEIGHT
import com.thain.duo.ResourceHelper.HINGE

public class PostureProcessorService : Service(), IHwBinder.DeathRecipient {
    private var sensorManager: SensorManager? = null
    private var postureSensor: Sensor? = null
    private var hallSensor: Sensor? = null
    private var currentDisplayComposition: Int = 5
    private var currentRotation: Int = -1
    private var displayHal: IDisplayTopology? = null
    private var touchHal: ITouchPen? = null
    private var windowManager: IWindowManager? = null
    private var displayManager: IDisplayManager? = null
    private var powerManager: PowerManager? = null
    private var currentPosture: Posture? = null
    private var pendingPosture: Posture? = null

    private val postureSensorListener: PostureSensorListener = PostureSensorListener()
    private val hallSensorListener: HallSensorListener = HallSensorListener()

    private val rotationWatcher: IRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            pendingPosture?.let {
                Log.d(TAG, "Rotation changed to ${rotation}, pending posture: ${it.posture.name} - ${it.rotation.name}")

                if ((windowManager?.isRotationFrozen() ?: false) && rotation == it.rotation.value) {
                    currentPosture = pendingPosture
                    pendingPosture = null
                    processPosture(currentPosture ?: Posture(PostureSensorValue.FlatDualP, Rotation.R0))
                }
            }
        }
    }

    private var PANEL_X: Int = 1350
    private var PANEL_Y: Int = 1350
    private var HINGE_GAP: Int = 84

    private var PANEL_OFFSET = (PANEL_X + HINGE_GAP) / 2

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        PANEL_X = applicationContext.resources.getInteger(WIDTH)
        PANEL_Y = applicationContext.resources.getInteger(HEIGHT)
        HINGE_GAP = applicationContext.resources.getInteger(HINGE)
        PANEL_OFFSET = (PANEL_X + HINGE_GAP) / 2

        val disableHingeVal = "1"

        val disabledHinge = SystemProperties.get("persist.sys.phh.duo.disable_hinge", "0") == disableHingeVal

        if (disabledHinge) {
            PANEL_OFFSET = PANEL_X / 2
        }

        setBezel(0)

        Log.d(TAG, "Loaded X: ${PANEL_X} Y: ${PANEL_Y} HINGE: ${HINGE_GAP} OFFSET: ${PANEL_OFFSET}")

        sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        postureSensor = sensorManager!!.getSensorList(Sensor.TYPE_ALL).stream().filter { s -> 
            s.getStringType().contains("microsoft.sensor.posture")
        }.findFirst().orElse(null)
        postureSensor?.let {
            sensorManager!!.registerListener(postureSensorListener, postureSensor, SensorManager.SENSOR_DELAY_UI)
        }

        hallSensor = sensorManager!!.getSensorList(Sensor.TYPE_ALL).stream().filter { s -> 
            s.getStringType().contains("microsoft.sensor.hall_effect")
        }.findFirst().orElse(null)
        hallSensor?.let {
            sensorManager!!.registerListener(hallSensorListener, hallSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        windowManager = WindowManagerGlobal.getWindowManagerService()
        if (windowManager == null) {
            Log.e(TAG, "Cannot get Window Manager")
        }

        windowManager?.watchRotation(rotationWatcher, DEFAULT_DISPLAY)

        displayManager = DisplayManagerGlobal.getDisplayManagerService();
        if (displayManager == null) {
            Log.e(TAG, "Cannot get Display Manager")
        }

        powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

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
            // if (currentDisplayComposition != composition) {
                Log.d(TAG, "Setting display composition ${composition}")
                displayHal?.setComposition(composition)
                touchHal?.setDisplayState(composition)
                currentDisplayComposition = composition
            // }
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set composition", e)
        }
    }

    private fun getBezel(): Int {
        try {
            connectHalIfNeeded()
            // if (currentDisplayComposition != composition) {
                return displayHal?.getBezelSize() ?: -1
            // }
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot get bezel", e)
            return -1
        }
    }

    private fun setBezel(bezelSize: Int) {
        try {
            connectHalIfNeeded()
            displayHal?.setBezelSize(bezelSize)
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set bezel", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        postureSensor?.let {
            sensorManager!!.unregisterListener(postureSensorListener, postureSensor)
        }

        hallSensor?.let {
            sensorManager!!.unregisterListener(hallSensorListener, hallSensor)
        }

        windowManager?.let {
            it.removeRotationWatcher(rotationWatcher)
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

    enum class DeviceState(val value: Int) {
        CLOSED(0),
        HALF_OPEN(1),
        FLAT(2),
        FOLDED(3)
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

    private fun isRotationLocked(): Boolean {
        return Settings.System.getIntForUser(applicationContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) == 0;
    }

    private fun isPortraitPosture(posture: PostureSensorValue): Boolean {
        when (posture) {
            PostureSensorValue.Book,
            PostureSensorValue.FlatDualP,
            PostureSensorValue.PeekLeft,
            PostureSensorValue.PeekRight,
            PostureSensorValue.BrochureRight, 
            PostureSensorValue.FlipPRight,
            PostureSensorValue.BrochureLeft, 
            PostureSensorValue.FlipPLeft -> {
                return true
            }

            else -> {
                return false
            }
        } 
    }

    private fun processPosture(newPosture: Posture) {
        Log.d(TAG, "Loaded X: ${PANEL_X} Y: ${PANEL_Y} HINGE: ${HINGE_GAP} OFFSET: ${PANEL_OFFSET}")
        Log.d(TAG, "Processing posture ${newPosture.posture.name} : ${newPosture.rotation.name}")

        setRotation(newPosture.rotation.value)

        when (newPosture.posture) {
            PostureSensorValue.Book,
            PostureSensorValue.Palette,
            PostureSensorValue.PeekLeft,
            PostureSensorValue.PeekRight -> {
                DeviceStateManagerGlobal.getInstance().requestState(DeviceStateRequest.newBuilder(DeviceState.HALF_OPEN.value).build(), null, null)
            }

            PostureSensorValue.FlatDualP,
            PostureSensorValue.FlatDualL -> {
                DeviceStateManagerGlobal.getInstance().requestState(DeviceStateRequest.newBuilder(DeviceState.FLAT.value).build(), null, null)
            }

            PostureSensorValue.Closed -> {
                DeviceStateManagerGlobal.getInstance().requestState(DeviceStateRequest.newBuilder(DeviceState.CLOSED.value).build(), null, null)
            }

            else -> {
                DeviceStateManagerGlobal.getInstance().requestState(DeviceStateRequest.newBuilder(DeviceState.FOLDED.value).build(), null, null)
            }
        }

        when (newPosture.posture) {
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
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)

                if (newPosture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                }
            }

            PostureSensorValue.BrochureLeft, PostureSensorValue.FlipPLeft -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                
                if (newPosture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                }
            }

            PostureSensorValue.TentRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
            }

            PostureSensorValue.TentLeft ->
            {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
            }

            PostureSensorValue.RampRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
            }
            
            PostureSensorValue.RampLeft -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
            }

            PostureSensorValue.FlipLRight -> {
                setComposition(1)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                if (newPosture.rotation == Rotation.R90) {
                    // windowManager?.freezeRotation(1)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                } else {
                    // windowManager?.freezeRotation(3)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                }
            }

            PostureSensorValue.FlipLLeft -> {
                setComposition(0)
                windowManager?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                if (newPosture.rotation == Rotation.R90) {
                    // windowManager?.freezeRotation(1)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                } else {
                    // windowManager?.freezeRotation(3)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                }
            }

            else -> {
                Log.d(TAG, "Unhandled posture");
            }
        }
    }

    inner class PostureSensorListener : SensorEventListener {
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
            val newPosture = Posture(sensorValue, Rotation from event.values[1].toInt())

            Log.d(TAG, "Got posture ${newPosture.posture.name} : ${newPosture.rotation.name}")

            val isRotationLocked = windowManager?.isRotationFrozen() ?: false

            if (currentPosture == null) {
                currentPosture = newPosture
                Log.d(TAG, "Updating posture because first posture")
            } else {
                currentPosture?.let {
                    if (it.posture != newPosture.posture || it.rotation.value != newPosture.rotation.value) {
                        if (newPosture.posture == PostureSensorValue.Closed || it.posture == PostureSensorValue.Closed) {
                            currentPosture = newPosture
                            Log.d(TAG, "Updating posture because previous or new are closed")
                        } else {
                            // Check rotation
                            if (isRotationLocked) {
                                // If the same orientation then assign
                                if (isPortraitPosture(it.posture) == isPortraitPosture(newPosture.posture)) {
                                    currentPosture = newPosture
                                    Log.d(TAG, "Updating posture because same orientation")
                                } else {
                                    pendingPosture = newPosture
                                }
                            } else {
                                currentPosture = newPosture;
                                Log.d(TAG, "Updating posture because not rotation locked")
                            }
                        }
                    }
                }
            }

            currentPosture?.let {
                processPosture(it)
                // windowManager?.thawRotation();
                

                // if (isRotationLocked) {
                //     windowManager?.freezeRotation(it.rotation.value)
                // }
            }

            
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        }
    }

    inner class HallSensorListener : SensorEventListener {
        private var currentHallValue: Int = 0

        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val hallValue = sensorEvent.values[0].toInt()
            Log.d(TAG, "hall value: " + hallValue)

            if (hallValue == 0) {
                powerManager?.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH, 0)
            } else if (currentHallValue == 0) {
                powerManager?.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_LID, "hall opened");
            }

            currentHallValue = hallValue
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        }
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