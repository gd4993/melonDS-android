package me.magnum.melonds.ui.emulator.input

import android.content.Context
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Detects two-finger gestures over the DS screens without interfering with regular touch input.
 *
 * A gesture is only considered when both fingers land on a DS screen (or an empty area) within a short time window. Until
 * a gesture is recognized, every event keeps flowing to the regular input handlers. Once recognized, the caller must
 * cancel any in-flight child touches and swallow the rest of the stream until all fingers are lifted.
 *
 * Recognized gestures:
 * - Pinch out: expand the screen under the fingers
 * - Pinch in: restore the regular layout
 * - Two-finger swipe (any direction): swap screens
 */
class ScreenGestureDetector(
    context: Context,
    private val isEligiblePoint: (x: Float, y: Float) -> Boolean,
    private val listener: Listener,
) {

    interface Listener {
        fun onPinchOut(focusX: Float, focusY: Float)
        fun onPinchIn(focusX: Float, focusY: Float)
        fun onTwoFingerSwipe(deltaX: Float, deltaY: Float)
    }

    enum class Result {
        /** Deliver the event to children as usual */
        PASS_THROUGH,
        /** A gesture was just recognized. Children must receive ACTION_CANCEL and this event must be consumed */
        GESTURE_STARTED,
        /** A recognized gesture is in progress. Consume the event */
        CONSUME,
    }

    private enum class State {
        IDLE,
        WAITING_SECOND_POINTER,
        TRACKING,
        CONSUMING,
        IGNORING,
    }

    private companion object {
        const val SECOND_POINTER_WINDOW_MS = 350L
        const val PINCH_THRESHOLD_DP = 40f
        const val PINCH_OUT_RATIO = 1.3f
        const val PINCH_IN_RATIO = 0.77f
        const val SWIPE_THRESHOLD_DP = 72f
        const val SWIPE_MIN_POINTER_TRAVEL_DP = 48f
        const val SWIPE_MIN_DIRECTION_COSINE = 0.7f
    }

    private val density = context.resources.displayMetrics.density
    private val pinchThresholdPx = PINCH_THRESHOLD_DP * density
    private val swipeThresholdPx = SWIPE_THRESHOLD_DP * density
    private val swipeMinPointerTravelPx = SWIPE_MIN_POINTER_TRAVEL_DP * density

    private var state = State.IDLE
    private var firstDownTime = 0L

    private var firstPointerId = MotionEvent.INVALID_POINTER_ID
    private var secondPointerId = MotionEvent.INVALID_POINTER_ID
    private var firstStartX = 0f
    private var firstStartY = 0f
    private var secondStartX = 0f
    private var secondStartY = 0f
    private var startSpan = 0f
    private var startFocusX = 0f
    private var startFocusY = 0f

    /**
     * Whether the detector wants to keep receiving the current touch stream even if no child consumed the initial event.
     */
    fun wantsTouchStream(): Boolean {
        return state == State.WAITING_SECOND_POINTER || state == State.TRACKING || state == State.CONSUMING
    }

    fun reset() {
        state = State.IDLE
        firstPointerId = MotionEvent.INVALID_POINTER_ID
        secondPointerId = MotionEvent.INVALID_POINTER_ID
    }

    fun onTouchEvent(event: MotionEvent): Result {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                firstPointerId = event.getPointerId(0)
                firstDownTime = event.eventTime
                state = if (isEligiblePoint(event.x, event.y)) State.WAITING_SECOND_POINTER else State.IGNORING
                return Result.PASS_THROUGH
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (state == State.CONSUMING) {
                    return Result.CONSUME
                }

                val newPointerIndex = event.actionIndex
                val canTrack = state == State.WAITING_SECOND_POINTER &&
                        event.pointerCount == 2 &&
                        event.eventTime - firstDownTime <= SECOND_POINTER_WINDOW_MS &&
                        isEligiblePoint(event.getX(newPointerIndex), event.getY(newPointerIndex))

                if (canTrack) {
                    val firstIndex = event.findPointerIndex(firstPointerId)
                    if (firstIndex < 0 || firstIndex == newPointerIndex) {
                        state = State.IGNORING
                        return Result.PASS_THROUGH
                    }

                    secondPointerId = event.getPointerId(newPointerIndex)
                    firstStartX = event.getX(firstIndex)
                    firstStartY = event.getY(firstIndex)
                    secondStartX = event.getX(newPointerIndex)
                    secondStartY = event.getY(newPointerIndex)
                    startSpan = hypot(secondStartX - firstStartX, secondStartY - firstStartY)
                    startFocusX = (firstStartX + secondStartX) / 2f
                    startFocusY = (firstStartY + secondStartY) / 2f
                    state = State.TRACKING
                } else {
                    // A third finger, a late second finger or a finger over a button. Leave this touch stream alone
                    state = State.IGNORING
                }
                return Result.PASS_THROUGH
            }
            MotionEvent.ACTION_MOVE -> {
                return when (state) {
                    State.CONSUMING -> Result.CONSUME
                    State.TRACKING -> evaluateTrackedPointers(event)
                    else -> Result.PASS_THROUGH
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                return when (state) {
                    State.CONSUMING -> Result.CONSUME
                    State.TRACKING -> {
                        // A finger was lifted before any gesture was recognized
                        state = State.IGNORING
                        Result.PASS_THROUGH
                    }
                    else -> Result.PASS_THROUGH
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasConsuming = state == State.CONSUMING
                reset()
                return if (wasConsuming) Result.CONSUME else Result.PASS_THROUGH
            }
        }

        return if (state == State.CONSUMING) Result.CONSUME else Result.PASS_THROUGH
    }

    private fun evaluateTrackedPointers(event: MotionEvent): Result {
        val firstIndex = event.findPointerIndex(firstPointerId)
        val secondIndex = event.findPointerIndex(secondPointerId)
        if (firstIndex < 0 || secondIndex < 0) {
            state = State.IGNORING
            return Result.PASS_THROUGH
        }

        val firstX = event.getX(firstIndex)
        val firstY = event.getY(firstIndex)
        val secondX = event.getX(secondIndex)
        val secondY = event.getY(secondIndex)

        val span = hypot(secondX - firstX, secondY - firstY)
        val spanDelta = span - startSpan

        // Pinch out
        if (spanDelta >= pinchThresholdPx && span >= startSpan * PINCH_OUT_RATIO) {
            state = State.CONSUMING
            listener.onPinchOut(startFocusX, startFocusY)
            return Result.GESTURE_STARTED
        }

        // Pinch in
        if (-spanDelta >= pinchThresholdPx && span <= startSpan * PINCH_IN_RATIO) {
            state = State.CONSUMING
            listener.onPinchIn(startFocusX, startFocusY)
            return Result.GESTURE_STARTED
        }

        // Two-finger swipe: both fingers travel a good distance in roughly the same direction
        val firstDx = firstX - firstStartX
        val firstDy = firstY - firstStartY
        val secondDx = secondX - secondStartX
        val secondDy = secondY - secondStartY
        val firstTravel = hypot(firstDx, firstDy)
        val secondTravel = hypot(secondDx, secondDy)

        if (firstTravel >= swipeMinPointerTravelPx && secondTravel >= swipeMinPointerTravelPx) {
            val focusDx = (firstX + secondX) / 2f - startFocusX
            val focusDy = (firstY + secondY) / 2f - startFocusY
            val focusTravel = hypot(focusDx, focusDy)
            val directionCosine = (firstDx * secondDx + firstDy * secondDy) / (firstTravel * secondTravel)

            if (focusTravel >= swipeThresholdPx && directionCosine >= SWIPE_MIN_DIRECTION_COSINE && kotlin.math.abs(spanDelta) < focusTravel * 0.5f) {
                state = State.CONSUMING
                listener.onTwoFingerSwipe(focusDx, focusDy)
                return Result.GESTURE_STARTED
            }
        }

        return Result.PASS_THROUGH
    }
}
