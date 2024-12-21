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

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun getBatteryPercentage(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun getDisplayText(context: Context): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = formatter.format(Date())
        val batteryPercentage = getBatteryPercentage(context)
        return "🕔 $formattedTime | 🔋 $batteryPercentage%"
    }

    fun showOverlay(isLeft: Boolean) {
        val displayText = getDisplayText(context)
        
        // Inflate the overlay view
        overlayView = LayoutInflater.from(context).inflate(R.layout.peak_mode_overlay, null)

        // Set the time on the left and right clocks
        val clock = overlayView?.findViewById<TextView>(R.id.clock)
        
        
        if (clock != null ) {
            clock.text = displayText
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

        if (isLeft) {
            clock!!.rotation = 90f
            val clockParams = clock!!.layoutParams as RelativeLayout.LayoutParams
            clockParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE)
            clock!!.layoutParams = clockParams
        } else {
            clock!!.rotation = 270f
            val clockParams = clock!!.layoutParams as RelativeLayout.LayoutParams
            clockParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE)
            clock!!.layoutParams = clockParams
        }

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