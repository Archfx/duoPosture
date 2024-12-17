package com.thain.duo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.devicestate.DeviceStateManagerGlobal;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.PixelFormat
import android.provider.Settings
import android.os.IBinder
import android.os.IHwBinder
import android.os.Handler
import android.os.HwRemoteBinder
import android.os.Looper
import android.os.Message
import android.os.RemoteException
import android.os.PowerManager
import android.os.SystemClock
import android.os.SystemProperties
import android.os.UserHandle
import android.view.Display.DEFAULT_DISPLAY
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.IRotationWatcher
import android.view.IWindowManager
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.view.View
import android.util.Log

import kotlinx.coroutines.runBlocking

import vendor.surface.displaytopology.V1_2.IDisplayTopology

import vendor.surface.touchpen.V1_0.ITouchPen as ITouchPenV1_0
import vendor.surface.touchpen.V1_3.ITouchPen as ITouchPenV1_2

import com.thain.duo.ResourceHelper.WIDTH
import com.thain.duo.ResourceHelper.HEIGHT
import com.thain.duo.ResourceHelper.HINGE

public class PostureProcessorService : Service(), IHwBinder.DeathRecipient {
    private var sensorManager: SensorManager? = null
    private var postureSensor: Sensor? = null
    private var hallSensor: Sensor? = null
    private var hingeSensor: Sensor? = null
    private var currentDisplayComposition: Int = 5
    private var currentTouchComposition: Int = -1
    private var currentRotation: Rotation = Rotation.RUnknown
    private var displayHal: IDisplayTopology? = null
    private var touchHal: ITouchPenV1_0? = null
    private var touchHalV2: ITouchPenV1_2? = null
    private var systemWm: IWindowManager? = null
    private var userWm: WindowManager? = null
    private var displayManager: IDisplayManager? = null
    private var powerManager: PowerManager? = null
    private var currentPosture: Posture? = null
    private var pendingPosture: Posture? = null
    private var postureOverlay: View? = null

    private var postureOverlayShown: Boolean = false

    private var previousTablet: Boolean = true
    private var isDuo2: Boolean = true

    private var postureLockVal: PostureLockSetting = PostureLockSetting.Dynamic

    private var wirelessChargingIntentFilter: IntentFilter? = null

    private lateinit var peakModeOverlay: PeakModeOverlay

    private val handler: Handler = object: Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_DISPLAY_LEFT -> {
                    setDisplay(0)
                    setTouch(0)
                }

                MSG_DISPLAY_RIGHT -> {
                    setDisplay(1)
                    setTouch(1) 
                }

                MSG_DISPLAY_BOTH -> {
                    setDisplay(2)
                    setTouch(2) 
                }

                MSG_TURN_OFF_SENSORS -> {
                    Log.d(TAG, "Hall closed. Deregister sensors after timeout")
                    sensorManager!!.unregisterListener(postureSensorListener, postureSensor)
                    sensorManager!!.unregisterListener(hingeAngleSensorListener, hingeSensor)
                }

                MSG_SHOW_POSTURE -> {
                    if (!postureOverlayShown) {
                        showPostureOverlay()
                        postureOverlayShown = true
                    }
                }

                MSG_HIDE_POSTURE -> {
                    if (postureOverlayShown) {
                        hidePostureOverlay()
                        postureOverlayShown = false
                    }
                }
            }
        }
    }

    private val postureSensorListener: PostureSensorListener = PostureSensorListener()
    private val hallSensorListener: HallSensorListener = HallSensorListener()
    private val hingeAngleSensorListener: HingeAngleSensorListener = HingeAngleSensorListener()

    private val rotationWatcher: IRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            pendingPosture?.let {
                Log.d(TAG, "Rotation changed to ${rotation}, pending posture: ${it.posture.name} - ${it.rotation.name}")

                if ((systemWm?.isRotationFrozen() ?: false) && rotation == it.rotation.value) {
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

        // Set the System setting in PHH to select from 3 Int vars, Dynamic (0), Right(1), Left(2)
        try{
            postureLockVal = PostureLockSetting.fromInt(SystemProperties.get("persist.sys.phh.duo.posture_lock", "0").toInt())
        } catch (e : NumberFormatException){
            postureLockVal = PostureLockSetting.Dynamic
        }
        
        if (disabledHinge) {
            PANEL_OFFSET = PANEL_X / 2
        }

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

        hingeSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
        hingeSensor?.let {
            sensorManager!!.registerListener(hingeAngleSensorListener, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        systemWm = WindowManagerGlobal.getWindowManagerService()
        if (systemWm == null) {
            Log.e(TAG, "Cannot get Window Manager")
        }

        systemWm?.watchRotation(rotationWatcher, DEFAULT_DISPLAY)

        userWm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (userWm == null) {
            Log.e(TAG, "cannot get user Window Manager")
        } else {            
            createPostureOverlay()
        }

        displayManager = DisplayManagerGlobal.getDisplayManagerService();
        if (displayManager == null) {
            Log.e(TAG, "Cannot get Display Manager")
        }

        powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

        connectHal()

        peakModeOverlay = PeakModeOverlay(this)

    }


    private fun createPostureOverlay() {
        val inflater = LayoutInflater.from(applicationContext)
        postureOverlay = inflater.inflate(R.layout.posture_overlay, null)
        
    }

    private fun showPostureOverlay() {
        Log.d(TAG, "Showing posture overlay")
        val windowParams = WindowManager.LayoutParams()
        windowParams.width = WindowManager.LayoutParams.MATCH_PARENT
        windowParams.height = WindowManager.LayoutParams.MATCH_PARENT
        windowParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        windowParams.format = PixelFormat.TRANSLUCENT
        windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        userWm?.addView(postureOverlay, windowParams)
    }

    private fun hidePostureOverlay() {
        Log.d(TAG, "Hiding posture overlay")
        userWm?.removeView(postureOverlay)
    }

    private fun connectTouchHal() {
        if (isDuo2){
            try {
                // checking if device is duo2
                touchHalV2 = ITouchPenV1_2.getService(true)
                touchHalV2?.linkToDeath(this, TOUCHPEN_HAL_DEATH_COOKIE)
            } catch (e: Throwable) {
                Log.d(TAG, "Could not connect to Touch HAL 1.2, trying 1.0")
                isDuo2 = false
            } 
        }

        if (!isDuo2) {
            try {
                // Then the device should be a duo1
                touchHal = ITouchPenV1_0.getService(true)
                touchHal?.linkToDeath(this, TOUCHPEN_HAL_DEATH_COOKIE)
            } catch (e: Throwable) {
                Log.d(TAG, "Could not connect to Touch HAL 1.0")
            } 
        }
    }

    private fun touchHulAngleSet(angle: Int) {
        if (!isDuo2) touchHal?.hingeAngle(angle)
        else touchHalV2?.hingeAngle(angle)
    }

    private fun touchHulCompSet(composition: Int) {
        if (!isDuo2) touchHal?.setDisplayState(composition)
        else touchHalV2?.setDisplayState(composition)    
    }

    private fun connectHalIfNeeded() {
        if (displayHal == null || (touchHal == null && !isDuo2) || (touchHalV2 == null && isDuo2) ) {
            connectHal()
        }
    }



    private fun connectHal() {
        try {
            displayHal = IDisplayTopology.getService(true)
            displayHal?.linkToDeath(this, DISPLAY_HAL_DEATH_COOKIE)
            connectTouchHal()
            Log.d(TAG, "Connected to HAL")
        } catch (e: Throwable) {
            Log.e(TAG, "HAL not connected", e)
        }
    }

    private fun setRotation(rotation: Rotation) {
        try {
            connectHalIfNeeded()
            if (rotation != currentRotation) {
                Log.d(TAG, "Setting display rotation ${rotation}")
                currentRotation = rotation
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set rotation", e)
        }
    }

    private fun setComposition(composition: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting display composition ${composition} new!")

            handler.removeCallbacksAndMessages(null)

            displayHal?.setComposition(composition)
            touchHulCompSet(composition)
        
            currentTouchComposition = composition
            currentDisplayComposition = composition

        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set composition", e)
        }
    }

    private fun setDisplay(composition: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting display composition ${composition}")
            displayHal?.setComposition(composition)
            currentDisplayComposition = composition
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set display composition", e)
        }
    }

     private fun setTouch(composition: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting touch composition ${composition}")
            touchHulCompSet(composition)

            currentTouchComposition = composition
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set touch composition", e)
        }
    }

    private fun setHingeAngle(angle: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting hinge angle ${angle}")
            touchHulAngleSet(angle)
            if (angle > 4 && angle < 50) {
                peakModeOverlay.showOverlay()
            } else {
                peakModeOverlay.hideOverlay()
            }        
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set angle", e)
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

        systemWm?.let {
            it.removeRotationWatcher(rotationWatcher)
        }

        userWm?.let {
            it.removeView(postureOverlay)
        }

        peakModeOverlay.hideOverlay()

    }

    //Start sticky can start service without intent (aka null)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        connectHalIfNeeded()

        return Service.START_STICKY
    }

    public enum class PostureLockSetting(val value: Int) {
        Dynamic(0),
        Right(1),
        Left(2);
        
        companion object {
            fun fromInt(incomingValue: Int) = PostureLockSetting.values().first { it.value == incomingValue }
        }
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
        R270(3),
        RUnknown(-1);

        companion object {
            private val map = Rotation.values().associateBy { it.value }
            infix fun from(value: Int) = map[value] ?: R0
        }
    }

    data class Posture(var posture: PostureSensorValue, val rotation: Rotation)

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

    private fun isRightSidedPostures(posture: PostureSensorValue): Boolean{
        when (posture) {
            PostureSensorValue.BrochureRight,
            PostureSensorValue.TentRight,
            PostureSensorValue.FlipPRight, 
            PostureSensorValue.FlipLRight,
            PostureSensorValue.RampRight -> {
                return true
            }

            else -> {
                return false
            }
        }


    }

    private fun isLeftSidedPostures(posture: PostureSensorValue): Boolean{
        when (posture) {
            PostureSensorValue.BrochureLeft,
            PostureSensorValue.TentLeft,
            PostureSensorValue.FlipPLeft, 
            PostureSensorValue.FlipLLeft,
            PostureSensorValue.RampLeft -> {
                return true
            }

            else -> {
                return false
            }
        }
    }   

    private fun isDualScreenPosture(posture: PostureSensorValue): Boolean{
        when (posture) {
            PostureSensorValue.Closed,
            PostureSensorValue.PeekRight, 
            PostureSensorValue.PeekLeft,
            PostureSensorValue.Book,
            PostureSensorValue.Palette,
            PostureSensorValue.FlatDualP,
            PostureSensorValue.FlatDualL -> {
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

        setRotation(newPosture.rotation)

        when (newPosture.posture) {
            PostureSensorValue.Book,
            PostureSensorValue.Palette,
            PostureSensorValue.PeekLeft,
            PostureSensorValue.PeekRight -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(DeviceStateRequest.newBuilder(DeviceState.HALF_OPEN.value).build(), null, null)
            }

            PostureSensorValue.FlatDualP,
            PostureSensorValue.FlatDualL -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(DeviceStateRequest.newBuilder(DeviceState.FLAT.value).build(), null, null)
            }

            PostureSensorValue.Closed -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(DeviceStateRequest.newBuilder(DeviceState.CLOSED.value).build(), null, null)
            }

            else -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(DeviceStateRequest.newBuilder(DeviceState.FOLDED.value).build(), null, null)
            }
        }

        when (newPosture.posture) {
            PostureSensorValue.Closed -> {
                // Turn off the screen by requesting PowerManager to enter sleep mode
                // powerManager?.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD, 0)
                systemWm?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
                setComposition(2)
            }

            PostureSensorValue.Book,
            PostureSensorValue.Palette,
            PostureSensorValue.FlatDualP,
            PostureSensorValue.FlatDualL -> {
                systemWm?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
                setComposition(2)
                if (!previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = true
            }

            PostureSensorValue.BrochureRight, PostureSensorValue.FlipPRight -> {
                if (newPosture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                }

                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)

                setComposition(1)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
                
            }

            PostureSensorValue.BrochureLeft, PostureSensorValue.FlipPLeft -> {
                if (newPosture.rotation == Rotation.R0) {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                } else {
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                }

                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)

                setComposition(0)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            PostureSensorValue.TentRight -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                setComposition(1)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            PostureSensorValue.TentLeft ->
            {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                setComposition(0)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            PostureSensorValue.RampRight -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                setComposition(1)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }
            
            PostureSensorValue.RampLeft -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                setComposition(0)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            PostureSensorValue.FlipLRight -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                setComposition(1)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            PostureSensorValue.FlipLLeft -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                setComposition(0)
                if (previousTablet) { 
                    restartLauncher(this) 
                }
                previousTablet = false
            }

            else -> {
                Log.d(TAG, "Unhandled posture");
            }
        }
    }

    /*
        Updates the Posture dynamically, depending on what screen is facing up when the device is folded.
        Also processes the change in posture if the device is flipped while folded to show one screen.

        This is the original logic maintained by thai-ng and Archfx.
    */
    private fun dynamicallyTransformPosture(isRotationLocked: Boolean, newPosture: Posture ) {
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
                            currentPosture = newPosture
                            Log.d(TAG, "Updating posture because it should")
                        }
                    } else {
                        currentPosture = newPosture;
                        Log.d(TAG, "Updating posture because not rotation locked")
                    }
                }
            }
        }
    }

    // Ugly function, will need to be revised. Unsure of how to do so in kotlin.
    private fun getEquivalentPostureForSingleScreen(getRightEquivalent: Boolean, incomingPosture: PostureSensorValue): PostureSensorValue {
        if(getRightEquivalent){
            if(incomingPosture == PostureSensorValue.BrochureLeft){
                return PostureSensorValue.BrochureRight
            }

            if(incomingPosture == PostureSensorValue.TentLeft){
                return PostureSensorValue.TentRight
            }
            
            if(incomingPosture == PostureSensorValue.FlipPLeft){
                return PostureSensorValue.FlipPRight
            }

            if(incomingPosture == PostureSensorValue.FlipLLeft){
                return PostureSensorValue.FlipLRight
            }

            if(incomingPosture == PostureSensorValue.RampLeft){
                return PostureSensorValue.RampRight
            }
        }
        else{
            if(incomingPosture == PostureSensorValue.BrochureRight){
                return PostureSensorValue.BrochureLeft
            }

            if(incomingPosture == PostureSensorValue.TentRight){
                return PostureSensorValue.TentLeft
            }
            
            if(incomingPosture == PostureSensorValue.FlipPRight){
                return PostureSensorValue.FlipPLeft
            }

            if(incomingPosture == PostureSensorValue.FlipLRight){
                return PostureSensorValue.FlipLLeft
            }

            if(incomingPosture == PostureSensorValue.RampRight){
                return PostureSensorValue.RampLeft
            }
        }
        
        // Posture didn't get caught by the above, just return the original posture.
        return incomingPosture
    }

    

    /*
        Processes a Transform in posture only when the device is unfolded/folded to show one screen.
        If Right is selected, then force the screen to show only on the right.
        Same logic applies to the left if Left is selected in system settings.

        If the device is rotated such that the left screen is facing up while Right is selected, then this will never change 
        screen and stay on the right.

        - If your current posture is a single screen posture, disallow change to any other single screen formats.
        - If your current posture is a single screen posture but the next change is a dual screen posture, allow change.
    */
    private fun staticallyTransformPosture(setRight: Boolean, isRotationLocked: Boolean, newPosture: Posture ){
        Log.d(TAG, "Attempting a static posture transform, setRight: ${setRight}");
        // Keep a modifiable Variable using the val from before.
        var newPostureModifiable : Posture = newPosture;

        currentPosture?.let {
            // Change if either the rot or posture has changed.
            if (it.posture != newPostureModifiable.posture || it.rotation.value != newPostureModifiable.rotation.value) {
                //Check if the posture has changed from or is going to be closed.
                if (newPostureModifiable.posture == PostureSensorValue.Closed || it.posture == PostureSensorValue.Closed) {
                    currentPosture = newPostureModifiable
                    Log.d(TAG, "Updating posture because previous or new are closed")
                } else {
                    //If we are already in single screen, disallow changes unless it's to a dual screen posture.
                    var isCurrentPostureSingleScreen = isRightSidedPostures(it.posture) || isLeftSidedPostures(it.posture)
                    var allowPostureTransition = false

                    if(isCurrentPostureSingleScreen){
                        if(setRight){
                            allowPostureTransition = isRightSidedPostures(newPostureModifiable.posture) || isDualScreenPosture(newPostureModifiable.posture)
                        }
                        else{
                            allowPostureTransition = isLeftSidedPostures(newPostureModifiable.posture) || isDualScreenPosture(newPostureModifiable.posture)
                        }
                    }
                    else{
                        /*
                            Posture is not Single Screen, it is on transition to single screen.
                            If the new posture is not matching the correct posture lock value, we convert it to an equivalent posture that matches
                            the side we want to lock it to. Once converted, we set the current posture from this modified new posture.
                        */
                        var isNewPostureMatchingLock = false
                        if(setRight){
                            // Check if the new posture is matching the locked posture setting
                            isNewPostureMatchingLock = isRightSidedPostures(newPostureModifiable.posture)
                            
                            //If not, provide an equivalent and overwrite the newPosture.
                            if(!isNewPostureMatchingLock){
                                Log.d(TAG, "Converted ${newPostureModifiable.posture} ->");
                                newPostureModifiable.posture = getEquivalentPostureForSingleScreen(true, newPostureModifiable.posture)
                                Log.d(TAG, "to ${newPostureModifiable.posture}");
                            }

                            allowPostureTransition = true;
                        }
                        else{
                            // Check if the posture is matching the locked posture setting
                            isNewPostureMatchingLock = isLeftSidedPostures(newPostureModifiable.posture)

                            //If not, provide an equivalent and overwrite the newPosture.
                            if(!isNewPostureMatchingLock){
                                Log.d(TAG, "Converted ${newPostureModifiable.posture} ->");
                                newPostureModifiable.posture = getEquivalentPostureForSingleScreen(false, newPostureModifiable.posture)
                                Log.d(TAG, "to ${newPostureModifiable.posture}");
                            }

                            allowPostureTransition = true;
                        }
                    }

                    if(allowPostureTransition){
                        if (isRotationLocked) {
                            // If the same orientation then assign
                            if (isPortraitPosture(it.posture) == isPortraitPosture(newPostureModifiable.posture)) {
                                currentPosture = newPostureModifiable
                                Log.d(TAG, "Updating posture because same orientation")
                            } else {
                                pendingPosture = newPostureModifiable
                                currentPosture = newPostureModifiable
                                Log.d(TAG, "Updating posture because it should")
                            }
                        } else {
                            currentPosture = newPostureModifiable;
                            Log.d(TAG, "Updating posture because not rotation locked")
                        }
                    }
                }
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

            val isRotationLocked = systemWm?.isRotationFrozen() ?: false
            
            if (currentPosture == null) {
                currentPosture = newPosture
                Log.d(TAG, "Updating posture because first posture")
            } else{

                /*
                    Get the PostureLockVal and update it often enough. maybe here? There is probably a better way to               
                    poll this value and update it.
                    Set the System setting in PHH to select from 3 Int vars, Dynamic (0), Right(1), Left(2)
                */
                // Set the System setting in PHH to select from 3 Int vars, Dynamic (0), Right(1), Left(2)
                try{
                    postureLockVal = PostureLockSetting.fromInt(SystemProperties.get("persist.sys.phh.duo.posture_lock", "0").toInt())
                } catch (e : NumberFormatException){
                    postureLockVal = PostureLockSetting.Dynamic
                }
        
                when (postureLockVal) {
                    PostureLockSetting.Dynamic -> dynamicallyTransformPosture(isRotationLocked, newPosture)
                    PostureLockSetting.Right -> staticallyTransformPosture(true, isRotationLocked, newPosture)
                    PostureLockSetting.Left -> staticallyTransformPosture(false, isRotationLocked, newPosture)
                }
            }

            currentPosture?.let {
                // Log.d(TAG, "Sending posture ${currentPosture.posture.name} : ${currentPosture.rotation.name}")
                processPosture(it)
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
                handler.sendEmptyMessageDelayed(MSG_TURN_OFF_SENSORS, 1000)
                powerManager?.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH, 0)
            } else if (currentHallValue == 0) {
                handler.removeCallbacksAndMessages(null)
                powerManager?.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_LID, "hall opened");

                Log.d(TAG, "Hall wake up. Register sensors")
                postureSensor?.let {
                    sensorManager!!.registerListener(postureSensorListener, postureSensor, SensorManager.SENSOR_DELAY_UI)
                }

                hingeSensor?.let {
                    sensorManager!!.registerListener(hingeAngleSensorListener, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }

            currentHallValue = hallValue
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        }
    }

    inner class HingeAngleSensorListener : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val angle = sensorEvent.values[0].toInt()
            setHingeAngle(angle);
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        }
    }

    /**
     * HwBinder.DeathRecipient
     */

    override fun serviceDied(cookie: Long) {
        if ((cookie == DISPLAY_HAL_DEATH_COOKIE) || (cookie == TOUCHPEN_HAL_DEATH_COOKIE)) {
            Log.d(TAG, "HAL died!")
            // runBlocking {
                connectHal()
                setComposition(currentDisplayComposition)
            // }
        }
    }

    private fun restartLauncher(context: Context) {
        val intent = Intent("com.thain.duo.LAUNCHER_RESTART")
        val userHandle = UserHandle(UserHandle.USER_CURRENT)
        context.sendBroadcastAsUser(intent, userHandle)
        Log.d(TAG, "Archfx Sent the broadcast to launcher restart!")
    }
    

    companion object {
        const val DISPLAY_HAL_DEATH_COOKIE: Long = 1337
        const val TOUCHPEN_HAL_DEATH_COOKIE: Long = 1338
        const val TAG = "POSTURE PROCESSOR"
        const val MSG_DISPLAY_LEFT: Int = 0
        const val MSG_DISPLAY_RIGHT: Int = 1
        const val MSG_DISPLAY_BOTH: Int = 2
        const val MSG_TURN_OFF_SENSORS: Int = 420
        const val MSG_SHOW_POSTURE: Int = 5
        const val MSG_HIDE_POSTURE: Int = 6
    }
}
