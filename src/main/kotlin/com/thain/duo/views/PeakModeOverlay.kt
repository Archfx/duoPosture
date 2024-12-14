package com.thain.duo

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.GestureDetector

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun showOverlay(currentTime: String) {
        if (overlayView == null) {
            // Inflate the overlay view
            overlayView = LayoutInflater.from(context).inflate(R.layout.peak_mode_overlay, null)

            // Set the time on the left and right clocks
			val leftClock = overlayView?.findViewById<TextView>(R.id.left_clock)
			val rightClock = overlayView?.findViewById<TextView>(R.id.right_clock)
			
			// Safely set the text if the clocks are found
			leftClock?.text = currentTime
			rightClock?.text = currentTime

            // Define layout parameters for the overlay
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            params.gravity = android.view.Gravity.FILL

            // Add the overlay to the WindowManager
            windowManager.addView(overlayView, params)
        }
    }

    fun hideOverlay() {
        if (overlayView != null) {
            // Remove the overlay view
            windowManager.removeView(overlayView)
            overlayView = null
        }
    }
}