package com.thain.duo

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import android.os.BatteryManager
import java.text.SimpleDateFormat
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.Intent
import android.content.IntentFilter
import android.view.ViewGroup
import android.util.TypedValue
import android.content.res.Resources
import android.os.SystemProperties

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    private var handler: Handler? = null
    private var runnable: Runnable? = null
    private var fadeHandler: Handler? = null
    private var fadeRunnable: Runnable? = null

    enum class HingeClockPosition(val value: Int) {
        Center(0),
        Top(1),
        Bottom(2);

        companion object {
            fun fromInt(incomingValue: Int) = HingeClockPosition.values().first { it.value == incomingValue }
        }
    }

    private var selectedHingePosition: HingeClockPosition = HingeClockPosition.Center

    fun getBatteryPercentage(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun getBatteryEmoji(context: Context): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        // Plug Emoji
        if(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) == 100 && status == BatteryManager.BATTERY_STATUS_CHARGING){
            return "\uD83D\uDD0C"
        }

        // Lightning symbol
        if(status == BatteryManager.BATTERY_STATUS_CHARGING){
            return "⚡"
        }

        //Default to Battery symbol
        return "\uD83D\uDD0B"

    }

    fun getTimeText(context: Context): String {
        var formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        if(!DateFormat.is24HourFormat(context)){
            formatter = SimpleDateFormat("KK:mm a", Locale.getDefault())
        }
        val formattedTime = formatter.format(Date())
        return "${formattedTime}"
    }

    fun getDateText(context: Context): String {
        val formatter = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        val formattedTime = formatter.format(Date())
        return "${formattedTime}"
    }

    fun scaleView(v: View, startScale: Float, endScale: Float) {
        val anim: Animation = ScaleAnimation(
            1f, 1f,  // Start and end values for the X axis scaling
            startScale, endScale,  // Start and end values for the Y axis scaling
            Animation.RELATIVE_TO_SELF, 0f,  // Pivot point of X scaling
            Animation.RELATIVE_TO_SELF, 1f
        ) // Pivot point of Y scaling
        anim.setFillAfter(true) // Needed to keep the result of the animation
        anim.setDuration(1000)
        anim.setInterpolator(AccelerateDecelerateInterpolator())
        v.startAnimation(anim)
    }

    fun showOverlay(sleepAfterShowingOverlay: Boolean, hingeGapDisabled: Boolean = false) {  
        val displayText = getTimeText(context)
        val dateText = getDateText(context)
        var hingeClockMargins = 40f // DP Val!
        var hingeClocKVerticalMargin = 575f; // DP Val!

        try{
            selectedHingePosition = HingeClockPosition.fromInt(SystemProperties.get("persist.sys.phh.duo.peek_mode_hinge_clock_position", "0").toInt())
        } catch (e : NumberFormatException){
            selectedHingePosition = HingeClockPosition.Center
        }
        
        if(hingeGapDisabled)
        {
            hingeClockMargins = 15f // DP Val!
        }

        // Cleanup overlay handlers
        if(handler != null){
            try{
                runnable?.let { handler?.removeCallbacks(it) }
            }catch (e: Exception){
                e.printStackTrace()
            }
        }

        // Force the Overlay to cleanup
        hideOverlay(false)
        
        // Inflate the overlay view
        overlayView = LayoutInflater.from(context).inflate(R.layout.peak_mode_overlay, null)

        // Set the time on the left and right clocks
        // Duo2 had some issues with the overlay showing on only one screen, possibly due to the launcher not being restarted.
        val left_clock = overlayView?.findViewById<TextView>(R.id.left_clock)
        val left_battery = overlayView?.findViewById<TextView>(R.id.left_battery)
        val left_hinge_clock = overlayView?.findViewById<TextView>(R.id.left_hinge_clock)
        val right_clock = overlayView?.findViewById<TextView>(R.id.right_clock)
        val right_battery = overlayView?.findViewById<TextView>(R.id.right_battery)
        val right_hinge_clock = overlayView?.findViewById<TextView>(R.id.right_hinge_clock)
        val battery_background = overlayView?.findViewById<View>(R.id.battery_background)
        val parent_view = overlayView?.findViewById<View>(R.id.parent_layout)

        scaleView(battery_background!!, 0f, getBatteryPercentage(context).toFloat() / 100f)
        
        if (left_clock != null && right_clock != null && left_battery != null && right_battery != null && right_hinge_clock != null && left_hinge_clock != null) {
            var hinge_text = """${displayText} | ${getBatteryEmoji(context)}${getBatteryPercentage(context).toString()}%"""
            var battery_text = """${dateText} | ${getBatteryEmoji(context)}${getBatteryPercentage(context).toString()}%"""

            left_clock.text = displayText
            right_clock.text = displayText
            left_hinge_clock.text = hinge_text
            right_hinge_clock.text = hinge_text
            left_battery.text = battery_text
            right_battery.text = battery_text

            // Convert DP to PX
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                hingeClockMargins,
                Resources.getSystem().displayMetrics
            ).toInt()

            val verticalPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                hingeClocKVerticalMargin,
                Resources.getSystem().displayMetrics
            ).toInt()

            // Apply to hinge
            var leftHingeClockParams = left_hinge_clock.layoutParams as ViewGroup.MarginLayoutParams
            var rightHingeClockParams = right_hinge_clock.layoutParams as ViewGroup.MarginLayoutParams
            
            when(selectedHingePosition){
                HingeClockPosition.Top -> {
                    leftHingeClockParams.setMargins(0, 0, px, verticalPx)
                    rightHingeClockParams.setMargins(px, 0, 0, verticalPx)
                }
                HingeClockPosition.Bottom -> {
                    leftHingeClockParams.setMargins(0, verticalPx, px, 0)
                    rightHingeClockParams.setMargins(px, verticalPx, 0, 0)
                }
                HingeClockPosition.Center -> {
                    leftHingeClockParams.setMargins(0, 0, px, 0)
                    rightHingeClockParams.setMargins(px, 0, 0, 0)
                }
            }

            left_hinge_clock.layoutParams = leftHingeClockParams
            right_hinge_clock.layoutParams = rightHingeClockParams
        }
        if(parent_view != null){
            //Animate from 0 alpha
            parent_view.alpha = 0f
            animateParentOpacity(true)
        }

        // Define layout parameters for the overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )
        
        if(sleepAfterShowingOverlay){
            try {
                windowManager.addView(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Setup new handler/runnable pair to hide the overlay after 5 seconds
            handler = Handler(Looper.getMainLooper())
            runnable = Runnable{
                hideOverlay(true)
                turnScreenOff()
            }

            handler!!.postDelayed(runnable!!, 5000)
        }
        else{
            //Add normally without explict hide timer.
            try {
                windowManager.addView(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun animateParentOpacity(show: Boolean){
        var alphaToTarget = 1f
        if(!show){
            alphaToTarget = 0f
        }

        if(overlayView != null){
            val parent_view = overlayView?.findViewById<View>(R.id.parent_layout)
            parent_view?.animate()?.alpha(alphaToTarget)?.setInterpolator(AccelerateDecelerateInterpolator())?.setDuration(750)
        }
    }

    //Shut screen off with generic reason.
    fun turnScreenOff(){
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            powerManager?.goToSleep(SystemClock.uptimeMillis())
        } catch (e: Exception) {
            e.printStackTrace()
        } 
    }

    fun hideOverlay(shouldAnimate: Boolean = true) {
        // Destroy all Fade handlers
        if(fadeHandler != null){
            try{
                fadeRunnable?.let { fadeHandler?.removeCallbacks(it) }
            }catch (e: Exception){
                e.printStackTrace()
            }
        }

        if(!shouldAnimate){
            try {
                if (overlayView != null) {
                    windowManager.removeView(overlayView)
                    overlayView = null
                }
            } catch (e: Exception) {
            }
        
            return
        }

        //Animate the overlay disappearing, should happen in 1 second.
        animateParentOpacity(false)
        fadeHandler = Handler(Looper.getMainLooper())
        fadeRunnable = Runnable{
            try {
                if (overlayView != null) {
                    windowManager.removeView(overlayView)
                    overlayView = null
                }
            } catch (e: Exception) {
            }
        }

        fadeRunnable?.let{runnable -> fadeHandler?.postDelayed(runnable, 250)}
    }

}