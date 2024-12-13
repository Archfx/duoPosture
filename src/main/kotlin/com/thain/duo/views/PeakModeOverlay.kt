import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class PeakModeOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun showOverlay(currentTime: String) {
        if (overlayView == null) {
            // Inflate the overlay view
            overlayView = LayoutInflater.from(context).inflate(R.layout.peak_mode_overlay, null)

            // Set the time on the left and right clocks
            val leftClock: TextView = overlayView!!.findViewById(R.id.left_clock)
            val rightClock: TextView = overlayView!!.findViewById(R.id.right_clock)

            leftClock.text = currentTime
            rightClock.text = currentTime

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
