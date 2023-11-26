package com.thain.duo

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector

class DoubleTapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val gestureDetector = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            return true
        }
    })

    init {
        View.inflate(context, R.layout.double_tap, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }
}