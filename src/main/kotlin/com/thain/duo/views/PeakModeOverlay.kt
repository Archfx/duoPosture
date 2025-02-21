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
import java.util.Date
import java.util.Locale
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.Intent
import android.content.IntentFilter

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    private var handler: Handler? = null
    private var runnable: Runnable? = null
    private var fadeHandler: Handler? = null
    private var fadeRunnable: Runnable? = null

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
        val formatter = SimpleDateFormat("KK:mm a", Locale.getDefault())
        val formattedTime = formatter.format(Date())
        return "${formattedTime}"
    }

    fun getDateText(context: Context): String {
        val formatter = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        val formattedTime = formatter.format(Date())
        return "${formattedTime}"
    }

    fun showOverlay(sleepAfterShowingOverlay: Boolean) {  
        val displayText = getTimeText(context)
        val dateText = getDateText(context)

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

        var heightvar: Int = context.resources.displayMetrics.heightPixels

        var heightToAnimateTo: Float = heightvar.toFloat() * (getBatteryPercentage(context) / 100f)    
        
        battery_background?.animate()?.scaleY(heightToAnimateTo)?.setInterpolator(AccelerateDecelerateInterpolator())?.setDuration(3000);
        
        if (left_clock != null && right_clock != null && left_battery != null && right_battery != null && right_hinge_clock != null && left_hinge_clock != null) {
            var hinge_text = """${displayText} | ${getBatteryEmoji(context)}${getBatteryPercentage(context).toString()}%"""
            var battery_text = """${dateText} | ${getBatteryEmoji(context)}${getBatteryPercentage(context).toString()}%"""

            left_clock.text = displayText
            right_clock.text = displayText
            left_hinge_clock.text = hinge_text
            right_hinge_clock.text = hinge_text
            left_battery.text = battery_text
            right_battery.text = battery_text
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        fadeRunnable?.let{runnable -> fadeHandler?.postDelayed(runnable, 1000)}
    }

}