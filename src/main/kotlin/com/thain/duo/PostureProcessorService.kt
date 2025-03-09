package com.thain.duo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
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
    private var isDuo2: Boolean = false
    private var isHingeDisabled: Boolean = false

    private var postureLockVal: PostureLockSetting = PostureLockSetting.Dynamic

    private var pluggedInIntentFilter: IntentFilter? = null
    private var pluggedInBroadcastReceiver: PluggedInBroadcastReceiver? = null

    private lateinit var peakModeOverlay: PeakModeOverlay
    private var isPeakMode: Boolean = false
    private var isPeekModeEnabled: Boolean = true

    enum class PostureMode {
        Automatic,
        ManualLeft,
        ManualRight,
        ManualTablet
    }

    private val handler: Handler = object: Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_DISPLAY_LEFT -> {
                    setDisplay(0)
                }

                MSG_DISPLAY_RIGHT -> {
                    setDisplay(1) 
                }

                MSG_DISPLAY_BOTH -> {
                    setDisplay(2) 
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

    private var PANEL_X: Int = 1350
    private var PANEL_Y: Int = 1350
    private var HINGE_GAP: Int = 84

    private var PANEL_OFFSET = (PANEL_X + HINGE_GAP) / 2

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (SystemProperties.get("ro.hardware", "N/A") == "surfaceduo2") {
            isDuo2 = true
        }

        PANEL_X = applicationContext.resources.getInteger(WIDTH)
        PANEL_Y = applicationContext.resources.getInteger(HEIGHT)
        HINGE_GAP = applicationContext.resources.getInteger(HINGE)
        PANEL_OFFSET = (PANEL_X + HINGE_GAP) / 2

        //Default value for when enabled
        val defaultOn = "1"

        isHingeDisabled = SystemProperties.get("persist.sys.phh.duo.disable_hinge", "0") == defaultOn
        isPeekModeEnabled = SystemProperties.get("persist.sys.phh.duo.peek_mode_enabled", "0") == defaultOn

        // Set the System setting in PHH to select from 3 Int vars, Dynamic (0), Right(1), Left(2)
        try{
            postureLockVal = PostureLockSetting.fromInt(SystemProperties.get("persist.sys.phh.duo.posture_lock", "0").toInt())
        } catch (e : NumberFormatException){
            postureLockVal = PostureLockSetting.Dynamic
        }
        
        if (isHingeDisabled) {
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

        //On plugged in event, we want to re-show the overlay
        pluggedInBroadcastReceiver = PluggedInBroadcastReceiver()
        pluggedInIntentFilter = IntentFilter().also{intentFilter -> 
            intentFilter.addAction("android.intent.action.ACTION_POWER_CONNECTED")
            intentFilter.addAction("android.intent.action.ACTION_POWER_DISCONNECTED")
            this.registerReceiver(pluggedInBroadcastReceiver, intentFilter)
            Log.d(TAG, "Broadcast Receiver registered for plugged in event!")
        }

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
                touchHalV2 = ITouchPenV1_2.getService(true)
                touchHalV2?.linkToDeath(this, TOUCHPEN_HAL_DEATH_COOKIE)
            } catch (e: Throwable) {
                // Log.d(TAG, "Could not connect to Touch HAL 1.2, trying 1.0")
            } 
        }

        if (!isDuo2) {
            try {
                touchHal = ITouchPenV1_0.getService(true)
                touchHal?.linkToDeath(this, TOUCHPEN_HAL_DEATH_COOKIE)
            } catch (e: Throwable) {
                // Log.d(TAG, "Could not connect to Touch HAL 1.0")
            } 
        }
    }

    private fun touchHalAngleSet(angle: Int) {
        if (!isDuo2) touchHal?.hingeAngle(angle)
        else touchHalV2?.hingeAngle(angle)
    }

    private fun touchHalCompSet(composition: Int) {
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


    private fun setComposition(composition: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting display composition ${composition} new!")
            handler.removeCallbacksAndMessages(null)
            displayHal?.setComposition(composition)
            touchHalCompSet(composition)
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
            touchHalCompSet(composition)
            currentDisplayComposition = composition
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set display composition", e)
        }
    }

    private fun setHingeAngle(angle: Int) {
        try {
            connectHalIfNeeded()
            Log.d(TAG, "Setting hinge angle ${angle}")
            touchHalAngleSet(angle)       
            if (angle < 50) isPeakMode = true
            else isPeakMode = false
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot set angle", e)
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        postureSensor?.let {
            sensorManager!!.unregisterListener(postureSensorListener, postureSensor)
        }
        hallSensor?.let {
            sensorManager!!.unregisterListener(hallSensorListener, hallSensor)
        }
        userWm?.let {
            it.removeView(postureOverlay)
        }
        peakModeOverlay.hideOverlay()

        unregisterReceiver(pluggedInBroadcastReceiver) 
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

    // Posture Sensor Values
    public enum class PSValue(val value: Float) {
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
            infix fun from(value: Float) = PSValue.values().first { it.value == value }
        }
    }

    enum class DeviceState(val value: Int) {
        CLOSED(0),
        HALF_OPEN(1),
        FLAT(2),
        FOLDED(3)
    }

    // data class Posture(var posture: PSValue, val rotation: Rotation)

    data class Posture(var posture: PSValue)

    // Returns the posture type
    // 0: Tablet, 1: Left, 2: Right, 3: Closed 4: PeekLeft, 5: PeekRight
    private fun postureType(posture: PSValue): Int{
        when (posture) {
            PSValue.Book,
            PSValue.Palette,
            PSValue.FlatDualP,
            PSValue.FlatDualL -> return 0
            PSValue.BrochureLeft, PSValue.FlipPLeft, 
            PSValue.TentLeft, PSValue.RampLeft, PSValue.FlipLLeft -> return 1
            PSValue.BrochureRight, PSValue.FlipPRight, 
            PSValue.TentRight, PSValue.RampRight, PSValue.FlipLRight -> return 2
            PSValue.Closed -> return 3
            PSValue.PeekLeft -> return 4
            PSValue.PeekRight -> return 5
            else -> return -1
        }
    }

    private fun processPosture(newPosture: Posture) {

        when (newPosture.posture) {
            PSValue.Book,
            PSValue.Palette,
            PSValue.PeekLeft,
            PSValue.PeekRight -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(
                    DeviceStateRequest.newBuilder(DeviceState.HALF_OPEN.value).build(),
                    null,
                    null
                )
            }
            PSValue.FlatDualP,
            PSValue.FlatDualL -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(
                    DeviceStateRequest.newBuilder(DeviceState.FLAT.value).build(),
                    null,
                    null
                )
            }
            PSValue.Closed -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(
                    DeviceStateRequest.newBuilder(DeviceState.CLOSED.value).build(),
                    null,
                    null
                )
            }
            else -> {
                DeviceStateManagerGlobal.getInstance()?.requestState(
                    DeviceStateRequest.newBuilder(DeviceState.FOLDED.value).build(),
                    null,
                    null
                )
            }
        }

        var pt = postureType(newPosture.posture)

        // Duo2 can see the screen from the side, hiding overlay when not in 3 either
        if(isDuo2){
            if (pt != 3 || pt != 4 || pt != 5){
                peakModeOverlay.hideOverlay()
            }
        }
        else{
            // For Duo1
            if (pt != 4 || pt != 5) {
                peakModeOverlay.hideOverlay()
            }
        }

        
        when (pt) {
            0 -> {
                // Should only restart the launcher if the composition has changed.
                if (currentDisplayComposition != 2){
                    systemWm?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
                    setComposition(2)
                    restartLauncher(this, true)
                }
            }
            1 -> {
                if(currentDisplayComposition != 0){
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                    systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                    setComposition(0)
                    restartLauncher(this, false)
                }
            }
            2 -> {
                if(currentDisplayComposition != 1){
                    displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                    systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                    setComposition(1)
                    restartLauncher(this, false)
                }      
            }
            3 -> {
                systemWm?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
                setComposition(2)
                
                //Always show overlay in this posture. 
                if (isPeakMode && isDuo2 && isPeekModeEnabled) peakModeOverlay.showOverlay(false, isHingeDisabled)
            }

            //The overlay refuses to show on these ones on Duo2. Forcing Dual Display.
            4 -> {
                setComposition(2)
                if (isPeakMode && isPeekModeEnabled) peakModeOverlay.showOverlay(false, isHingeDisabled)
            }
            5 -> {
                setComposition(2)
                if (isPeakMode && isPeekModeEnabled) peakModeOverlay.showOverlay(false, isHingeDisabled)
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
    private fun dynamicallyTransformPosture( newPosture: Posture ) {
        currentPosture?.let {
            if (it.posture != newPosture.posture) {
                currentPosture = newPosture;
            }
        }
    }

    private fun getEquivalentPostureForSingleScreen(getRightEquivalent: Boolean, incomingPosture: PSValue): PSValue {
        return when {
            getRightEquivalent -> when (incomingPosture) {
                PSValue.BrochureLeft -> PSValue.BrochureRight
                PSValue.TentLeft -> PSValue.TentRight
                PSValue.FlipPLeft -> PSValue.FlipPRight
                PSValue.FlipLLeft -> PSValue.FlipLRight
                PSValue.RampLeft -> PSValue.RampRight
                else -> incomingPosture
            }
            else -> when (incomingPosture) {
                PSValue.BrochureRight -> PSValue.BrochureLeft
                PSValue.TentRight -> PSValue.TentLeft
                PSValue.FlipPRight -> PSValue.FlipPLeft
                PSValue.FlipLRight -> PSValue.FlipLLeft
                PSValue.RampRight -> PSValue.RampLeft
                else -> incomingPosture
            }
        }
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
    private fun staticallyTransformPosture(setRight: Boolean, newPosture: Posture ){
        Log.d(TAG, "Attempting a static posture transform, setRight: ${setRight}");
        // Keep a modifiable Variable using the val from before.
        var newPostureModifiable : Posture = newPosture;

        currentPosture?.let {
            // Change if either the rot or posture has changed.
            if (it.posture != newPostureModifiable.posture ) {
                //Check if the posture has changed from or is going to be closed.
                if (newPostureModifiable.posture == PSValue.Closed || it.posture == PSValue.Closed) {
                    currentPosture = newPostureModifiable
                    Log.d(TAG, "Updating posture because previous or new are closed")
                } else {
                    //If we are already in single screen, disallow changes unless it's to a dual screen posture.
                    var isCurrentPostureSingleScreen = (postureType(it.posture) == 2) || (postureType(it.posture) == 1)
                    var allowPostureTransition = false

                    if(isCurrentPostureSingleScreen){
                        if(setRight){
                            allowPostureTransition = (postureType(newPostureModifiable.posture) == 2) || (postureType(newPostureModifiable.posture) == 0)
                        }
                        else{
                            allowPostureTransition = (postureType(newPostureModifiable.posture) == 1) || (postureType(newPostureModifiable.posture) == 0)
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
                            isNewPostureMatchingLock = (postureType(newPostureModifiable.posture)==2)
                            
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
                            isNewPostureMatchingLock = (postureType(newPostureModifiable.posture) == 1)

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
                        currentPosture = newPostureModifiable;
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
            
            val sensorValue = PSValue from event.values[0]
            // val newPosture = Posture(sensorValue, Rotation from event.values[1].toInt())
            val newPosture = Posture(sensorValue)

            // Log.d(TAG, "Got posture ${newPosture.posture.name} : ${newPosture.rotation.name}")

            if (currentPosture == null) {
                currentPosture = newPosture
            } else {
                when (postureLockVal) {
                    PostureLockSetting.Dynamic -> dynamicallyTransformPosture(newPosture)
                    PostureLockSetting.Right -> staticallyTransformPosture(true, newPosture)
                    PostureLockSetting.Left -> staticallyTransformPosture(false, newPosture)
                }
            }
            
            if (postureMode == PostureMode.Automatic) {
                currentPosture?.let {
                    processPosture(it)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }
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

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }
    }

    inner class HingeAngleSensorListener : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val angle = sensorEvent.values[0].toInt()
            setHingeAngle(angle);
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }
    }

    inner class PluggedInBroadcastReceiver: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent){     
            val action: String? = intent.getAction()
            if(action == "android.intent.action.ACTION_POWER_CONNECTED" || action == "android.intent.action.ACTION_POWER_DISCONNECTED" ){
                // Get the current posture, check if it's either Closed and then show if it is one of these.
                var pt = postureType(currentPosture!!.posture)

                //Check if the posture is closed or are we a Duo2
                if(!isDuo2 || pt != 3){
                    return
                }
                
                // There shouldn't be any reason for the device to be active during a closed position,
                // This should show the overlay and then force the device to sleep after 5 seconds if we're in this position.
                if(isPeakMode && isPeekModeEnabled){
                    peakModeOverlay.showOverlay(true, isHingeDisabled)
                    Log.d(TAG, "Received action ${action} and posture is closed, showing Overlay")
                }
                
            }
        }
    }

    public fun setPosture(postureMode: Int) {
        when (postureMode) {
            0 -> {
                systemWm?.clearForcedDisplaySize(DEFAULT_DISPLAY)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, 0, 0)
                setComposition(2)
                restartLauncher(this, true)
            }
            1 -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, -PANEL_OFFSET, 0)
                setComposition(0)
                restartLauncher(this, false)
            }
            2 -> {
                systemWm?.setForcedDisplaySize(DEFAULT_DISPLAY, PANEL_X, PANEL_Y)
                displayManager?.setDisplayOffsets(DEFAULT_DISPLAY, PANEL_OFFSET, 0)
                setComposition(1)
                restartLauncher(this, false) 
            }
            3 -> {
                currentPosture?.let { processPosture(it) }                
            }
        }
        
    }

    /**
     * HwBinder.DeathRecipient
     */

    override fun serviceDied(cookie: Long) {
        if ((cookie == DISPLAY_HAL_DEATH_COOKIE) || (cookie == TOUCHPEN_HAL_DEATH_COOKIE)) {
            Log.d(TAG, "HAL died!")
                connectHal()
                setComposition(currentDisplayComposition)
        }
    }

    // Restart the launcher when transitioning from dual screen to single screen
    private fun restartLauncher(context: Context, isTablet: Boolean = true) {
        if (previousTablet) { 
            val intent = Intent("com.thain.duo.LAUNCHER_RESTART")
            val userHandle = UserHandle(UserHandle.USER_CURRENT)
            context.sendBroadcastAsUser(intent, userHandle)
        }
        previousTablet = isTablet
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
        var postureMode: PostureMode = PostureMode.Automatic
        private var instance: PostureProcessorService? = null

        @JvmStatic
        fun setManualPosture(postureMode: Int) {
            instance?.setPosture(postureMode)
        }

    }
}
