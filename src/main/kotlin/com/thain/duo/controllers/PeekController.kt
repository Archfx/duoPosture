package com.thain.duo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/**
 * One notification type on the peek strip (typically one app).
 *
 * @param count how many notifications of this type there are. The hinge strip shows the number
 *        on the box only when it is greater than 1.
 */
data class PeekNotification(val key: String, val icon: Drawable, val count: Int = 1)

/**
 * Battery state shown on every battery box.
 *
 * @param charging actively charging: drives the moving wave and the gradient pulse
 * @param pluggedIn on a charger (including full): drives the bolt icon
 */
data class BatteryState(val percent: Int, val charging: Boolean, val pluggedIn: Boolean)

/**
 * Where notification data comes from.
 * Prototype: a fake. AOSP: back this with the active-notifications state
 * SystemUI already exposes (group by package to get one entry + count per app).
 */
interface PeekNotificationSource {
    fun current(): List<PeekNotification>
    fun addListener(listener: (List<PeekNotification>) -> Unit)
    fun removeListener(listener: (List<PeekNotification>) -> Unit)
}

/**
 * Owns the Peek Mode window and its state.
 *
 * The whole overlay fades in when [show] is called and fades out when [hide] is called; the
 * window is only removed once the fade-out finishes.
 *
 * The layout has two identical outer-edge panels plus a strip along the hinge on each screen
 * (rotated 90 degrees: anticlockwise on the left screen, clockwise on the right). Each strip
 * holds the clock, a battery box, then one box per notification type. All bind functions
 * update every copy.
 *
 * Notification slots: at most [maxVisible] types are shown. If there are more, the next slot
 * becomes an ellipsis icon. On the hinge strip that ellipsis box also shows how many
 * notification TYPES are hidden (not how many notifications).
 *
 * @param hingeWidthPx hinge gap in RAW PIXELS within the single combined display
 *        (66 for the target device). It is deliberately not converted from dp.
 *        Kept as a parameter so it can be changed in one place.
 * @param maxVisible how many notification types are drawn before the ellipsis slot.
 */
class PeekController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val notifications: PeekNotificationSource,
    private val hingeWidthPx: Int = DEFAULT_HINGE_GAP_PX,
    private val maxVisible: Int = 5,
) {
    companion object {
        /** Hinge gap of the only device this targets, in pixels. Since this is only 
         *  useful for Duo2, we leave it at duo2 defaults.
         */
        const val DEFAULT_HINGE_GAP_PX = 66

        /**
         * The orientation Peek is always shown in, whatever the device's physical orientation.
         * The layout assumes the spanned display with a vertical hinge (left screen | hinge |
         * right screen), i.e. the wide orientation. If your device maps that to a different
         * rotation, change this one constant (e.g. SCREEN_ORIENTATION_REVERSE_LANDSCAPE).
         */
        const val FORCED_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        /** Fade of the whole overlay when it becomes active / is deactivated. */
        const val FADE_IN_MS = 300L
        const val FADE_OUT_MS = 300L
        const val TAG = "PostureProcessorPeekController"
    }

    private var peekView: View? = null

    /** True between [show] and [hide]. The window can outlive this briefly while it fades out. */
    private var visible = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }

    val isShowing: Boolean get() = visible

    private var batteryState: BatteryState? = null

    private val notifListener: (List<PeekNotification>) -> Unit = { bindNotifications(it) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = bindBattery(intent)
    }

    /**
     * Fades the overlay in. Calling it while it is fading out brings it back.
     * @param timeoutMs auto-hide delay, or null to stay until [hide] is called.
     */
    fun show(timeoutMs: Long? = null) {
        val view = peekView ?: createWindow()
        if (!visible) {
            visible = true

            // registerReceiver returns the last sticky battery broadcast: use it for first paint.
            val sticky = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) bindBattery(sticky) else applyBattery()
            notifications.addListener(notifListener)
            bindNotifications(notifications.current())

            view.animate().cancel() // also cancels a pending fade-out removal
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
        handler.removeCallbacks(autoHide)
        timeoutMs?.let { handler.postDelayed(autoHide, it) }
    }

    /** Fades the overlay out, then removes its window. */
    fun hide() {
        handler.removeCallbacks(autoHide)
        val view = peekView ?: return
        if (!visible) return
        visible = false

        // Stop live updates now; the view just stays up while it fades.
        notifications.removeListener(notifListener)
        runCatching { context.unregisterReceiver(batteryReceiver) }

        view.animate().cancel()
        // withEndAction only runs if the fade finishes; show() cancels it to bring the overlay back.
        view.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            view.visibility = View.INVISIBLE
            removeWindow(view)
        }.start()
    }

    private fun createWindow(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.peek_mode, null)
        view.findViewById<View>(R.id.peek_hinge_gap).layoutParams.width = hingeWidthPx
        view.alpha = 0f // faded in by show()
        peekView = view

        // Outer panels: battery background rounded on all corners (strips keep top-only).
        panels().forEach {
            it.findViewById<BatteryBoxView>(R.id.peek_panel_battery).setRoundAllCorners(true)
        }

        windowManager.addView(view, layoutParams())
        return view
    }

    private fun removeWindow(view: View) {
        if (peekView === view && !visible) {
            runCatching { windowManager.removeView(view) }
            peekView = null
        }
    }

    /** The two cloned outer panels. Child ids repeat inside each, so look them up per panel. */
    private fun panels(): List<View> {
        val view = peekView ?: return emptyList()
        return listOf(R.id.peek_panel_left, R.id.peek_panel_right)
            .mapNotNull { view.findViewById<View>(it) }
    }

    /** The two cloned hinge strips (left screen, right screen). */
    private fun strips(): List<View> {
        val view = peekView ?: return emptyList()
        return listOf(R.id.peek_strip_left, R.id.peek_strip_right)
            .mapNotNull { view.findViewById<View>(it) }
    }

    private fun bindBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING // drives the wave + pulse
        val pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 // drives the bolt icon
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0

        batteryState = BatteryState(pct, charging, pluggedIn)
        applyBattery()
    }

    /** Pushes the current battery state (the test override if set, else the real one) into every box. */
    private fun applyBattery() {
        val state = batteryState ?: return
        // Every battery box (outer panels + hinge strips) is the same view: percent, icon,
        // level-colored fill animating up from 0, and the wave + gradient pulse while charging.
        panels().forEach {
            it.findViewById<BatteryBoxView>(R.id.peek_panel_battery)
                .setLevel(state.percent, state.charging, state.pluggedIn)
        }
        strips().forEach {
            it.findViewById<BatteryBoxView>(R.id.peek_strip_battery)
                .setLevel(state.percent, state.charging, state.pluggedIn)
        }
    }

    private fun bindNotifications(list: List<PeekNotification>) {
        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt()
        val margin = (6 * density).toInt()
        val total = list.sumOf { it.count }
        val countLabel = when (total) {
            0 -> ""
            1 -> "1 notification"
            else -> "$total notifications"
        }

        // Types that get their own slot, and how many TYPES spill into the ellipsis slot.
        val visibleTypes = list.take(maxVisible)
        val hiddenTypes = list.size - visibleTypes.size

        // A Drawable instance shouldn't be shared between views, so every view gets its own copy.
        fun ownIcon(n: PeekNotification): Drawable =
            n.icon.constantState?.newDrawable()?.mutate() ?: n.icon
        fun moreIcon(): Drawable = context.getDrawable(R.drawable.ic_peek_more)!!

        // Outer-edge panels: icon grid (ellipsis icon in the overflow slot) + total count
        panels().forEach { panel ->
            val grid = panel.findViewById<GridLayout>(R.id.peek_notif_icons)
            val countText = panel.findViewById<TextView>(R.id.peek_notif_count)

            fun addIcon(drawable: Drawable) {
                val icon = ImageView(context).apply {
                    setImageDrawable(drawable)
                    setColorFilter(0xFFFFFFFF.toInt())
                }
                grid.addView(icon, GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(margin, margin, margin, margin)
                })
            }

            grid.removeAllViews()
            visibleTypes.forEach { addIcon(ownIcon(it)) }
            if (hiddenTypes > 0) addIcon(moreIcon())

            countText.text = countLabel
            countText.visibility = if (total == 0) View.GONE else View.VISIBLE
        }

        // Hinge strips: one rounded-top box per type. The number shows only when that type has
        // more than one notification. The overflow box is an ellipsis plus the hidden type count.
        val inflater = LayoutInflater.from(context)
        strips().forEach { strip ->
            val row = strip.findViewById<LinearLayout>(R.id.peek_strip_icons)

            fun addBox(drawable: Drawable, number: String?) {
                val box = inflater.inflate(R.layout.peek_notif_box, row, false)
                box.findViewById<ImageView>(R.id.peek_box_icon).setImageDrawable(drawable)
                box.findViewById<TextView>(R.id.peek_box_count).apply {
                    if (number == null) {
                        visibility = View.GONE
                    } else {
                        text = number
                        visibility = View.VISIBLE
                    }
                }
                row.addView(box)
            }

            row.removeAllViews()
            visibleTypes.forEach { n ->
                addBox(ownIcon(n), if (n.count > 1) n.count.toString() else null)
            }
            if (hiddenTypes > 0) addBox(moreIcon(), hiddenTypes.toString())
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        // Prototype only (needs "draw over other apps"). In SystemUI use a window type
        // SystemUI is allowed to add, the same way other full-display SystemUI windows do.
        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        // Translucent (not OPAQUE) so fading the root view's alpha reveals what's underneath
        // instead of fading black into black. The content itself is still opaque black at alpha 1.
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Pin the display to one orientation while Peek is up, so the view never rotates with the device.
        screenOrientation = FORCED_ORIENTATION
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
    }
}