package dev.ghostloc.offline.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The map lives inside a ViewPager2 page. Once a drag passes the pager's touch
 * slop, the pager's inner RecyclerView intercepts the gesture: the map stops
 * responding mid-drag and the page slides away. Claiming the whole gesture
 * chain for any touch that starts on the map keeps panning/fluid and confines
 * page switching to the tab strip and non-map controls.
 */
class MapTouchWrapper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }
}
