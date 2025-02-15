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

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun getBatteryPercentage(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
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

    fun showOverlay() {  
        val displayText = getTimeText(context)
        val dateText = getDateText(context)
        
        // Inflate the overlay view
        overlayView = LayoutInflater.from(context).inflate(R.layout.peak_mode_overlay, null)

        // Set the time on the left and right clocks
        // Duo2 had some issues with the overlay showing on only one screen, possibly due to the launcher not being restarted.
        val left_clock = overlayView?.findViewById<TextView>(R.id.left_clock)
        val left_battery = overlayView?.findViewById<TextView>(R.id.left_battery)
        val right_clock = overlayView?.findViewById<TextView>(R.id.right_clock)
        val right_battery = overlayView?.findViewById<TextView>(R.id.right_battery)
        val battery_background = overlayView?.findViewById<View>(R.id.battery_background)

        var heightvar: Int = context.resources.displayMetrics.heightPixels

        var heightToAnimateTo: Float = heightvar.toFloat() * (getBatteryPercentage(context) / 100f)    
        
        battery_background?.animate()?.scaleY(heightToAnimateTo)?.setInterpolator(AccelerateDecelerateInterpolator())?.setDuration(3000);
        
        if (left_clock != null && right_clock != null && left_battery != null && right_battery != null) {
            left_clock.text = displayText
            right_clock.text = displayText
            left_battery.text = """${dateText} | 🔋${getBatteryPercentage(context).toString()}%"""
            right_battery.text = """${dateText} | 🔋${getBatteryPercentage(context).toString()}%"""
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

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
                overlayView = null
            } catch (e: Exception) {
            }
        } 
    }

}