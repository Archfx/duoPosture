package com.thain.duo

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Hosts ONE child laid out as a normal horizontal row, then rotates it 90 degrees
 * (app:childRotation="90" for clockwise, "-90" for anticlockwise) so it runs vertically.
 *
 * Unlike android:rotation on the child, this swaps width/height for layout purposes: the
 * parent sees a tall, thin view of (child height) x (available height), so ConstraintLayout can
 * anchor it against an edge correctly. The child is measured as if it were lying along our
 * height, which makes `match_parent` width mean "the full length of the strip".
 */
class RotatedLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val degrees: Float

    init {
        clipChildren = false
        clipToPadding = false
        val a = context.obtainStyledAttributes(attrs, R.styleable.RotatedLayout)
        degrees = a.getFloat(R.styleable.RotatedLayout_childRotation, 90f)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null || child.visibility == GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val lp = child.layoutParams
        // The child's width runs along OUR height, and its height along OUR width.
        child.measure(
            getChildMeasureSpec(heightMeasureSpec, 0, lp.width),
            getChildMeasureSpec(widthMeasureSpec, 0, lp.height),
        )
        setMeasuredDimension(
            resolveSize(child.measuredHeight, widthMeasureSpec),
            resolveSize(child.measuredWidth, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        if (child.visibility == GONE) return

        val w = right - left
        val h = bottom - top
        val cw = child.measuredWidth
        val ch = child.measuredHeight

        // Lay the child out unrotated and centered, then rotate about its center.
        // Its unrotated box is wider than we are, but after the 90 degree turn it
        // exactly fills our (thin, tall) bounds.
        val cl = (w - cw) / 2
        val ct = (h - ch) / 2
        child.layout(cl, ct, cl + cw, ct + ch)
        child.pivotX = cw / 2f
        child.pivotY = ch / 2f
        child.rotation = degrees
    }
}
