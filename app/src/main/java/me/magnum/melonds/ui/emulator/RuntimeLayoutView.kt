package me.magnum.melonds.ui.emulator

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Point
import me.magnum.melonds.domain.model.input.SoftInputBehaviour
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.ui.common.LayoutComponentView
import me.magnum.melonds.ui.common.LayoutView
import me.magnum.melonds.ui.emulator.input.ButtonsInputHandler
import me.magnum.melonds.ui.emulator.input.DpadInputHandler
import me.magnum.melonds.ui.emulator.input.FrontendInputHandler
import me.magnum.melonds.ui.emulator.input.IInputListener
import me.magnum.melonds.ui.emulator.input.ScreenGestureDetector
import me.magnum.melonds.ui.emulator.input.SingleButtonInputHandler
import me.magnum.melonds.ui.emulator.input.TouchscreenInputHandler
import me.magnum.melonds.ui.emulator.input.view.ToggleableImageView
import me.magnum.melonds.ui.emulator.model.ConnectedControllersState
import me.magnum.melonds.ui.emulator.model.RuntimeInputLayoutConfiguration
import me.magnum.melonds.ui.layouteditor.model.LayoutTarget
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@AndroidEntryPoint
class RuntimeLayoutView(context: Context, attrs: AttributeSet? = null) : LayoutView(context, attrs) {

    @Inject
    lateinit var touchVibrator: TouchVibrator

    private var currentRuntimeLayout: RuntimeInputLayoutConfiguration? = null
    private var frontendInputHandler: IInputListener? = null
    private var systemInputHandler: IInputListener? = null
    private var isSoftInputVisible = true
    private var areScreensSwapped = false
    private var connectedControllersState: ConnectedControllersState = ConnectedControllersState.NoControllers

    /**
     * Invoked whenever the position, size or stacking of the screen views changes, so that the renderer can follow them.
     */
    var onScreenAreasChanged: (() -> Unit)? = null

    /**
     * Whether two-finger gestures over the screens are enabled (pinch to expand a screen, two-finger swipe to swap screens).
     */
    var areScreenGesturesEnabled = false
        set(value) {
            field = value
            screenGestureDetector.reset()
        }

    private val screenGestureDetector = ScreenGestureDetector(
        context = context,
        isEligiblePoint = ::isScreenGestureEligiblePoint,
        listener = object : ScreenGestureDetector.Listener {
            override fun onPinchOut(focusX: Float, focusY: Float) {
                post { onPinchOutGesture(focusX, focusY) }
            }

            override fun onPinchIn(focusX: Float, focusY: Float) {
                post { onPinchInGesture() }
            }

            override fun onTwoFingerSwipe(deltaX: Float, deltaY: Float) {
                post { onTwoFingerSwipeGesture() }
            }
        },
    )

    private data class ScreenPlacement(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class SavedScreenState(val placement: ScreenPlacement, val onTop: Boolean)

    /**
     * The screen slot (view) that is currently expanded, or null if the regular layout is being displayed.
     */
    private var expandedScreen: LayoutComponent? = null
    private val savedScreenStates = mutableMapOf<LayoutComponent, SavedScreenState>()
    private var originalLowerScreen: LayoutComponent? = null
    private var screenAnimator: ValueAnimator? = null
    private val screenLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            onScreenAreasChanged?.invoke()
        }
    }

    fun setFrontendInputHandler(frontendInputHandler: FrontendInputHandler) {
        this.frontendInputHandler = frontendInputHandler
        updateInputs()
    }

    fun setSystemInputHandler(systemInputHandler: IInputListener) {
        this.systemInputHandler = systemInputHandler
        updateInputs()
    }

    fun setConnectedControllersState(state: ConnectedControllersState) {
        connectedControllersState = state
        updateVisibility()
    }

    fun toggleSoftInputVisibility() {
        isSoftInputVisible = !isSoftInputVisible
        setLayoutComponentToggleState(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT, isSoftInputVisible)
        updateVisibility()
    }

    fun swapScreens() {
        areScreensSwapped = !areScreensSwapped
        updateScreenInputs()
    }

    fun areScreensSwapped(): Boolean {
        return areScreensSwapped
    }

    fun setLayoutComponentToggleState(layoutComponent: LayoutComponent, isEnabled: Boolean) {
        val toggleableImageView = getLayoutComponentView(layoutComponent)?.view as? ToggleableImageView ?: return
        toggleableImageView.setToggleState(isEnabled)
    }

    fun instantiateLayout(runtimeLayout: RuntimeInputLayoutConfiguration, layoutTarget: LayoutTarget) {
        val previouslyExpandedScreen = expandedScreen
        resetScreenExpansion()

        currentRuntimeLayout = runtimeLayout
        instantiateLayout(runtimeLayout.layout, layoutTarget)
        updateInputs()
        updateVisibility()
        setLayoutComponentToggleState(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT, isSoftInputVisible)

        if (previouslyExpandedScreen != null) {
            // Keep the expanded screen across layout changes (rotation, fold/unfold)
            post { expandScreen(previouslyExpandedScreen, animate = false) }
        }
    }

    fun isScreenExpanded(): Boolean {
        return expandedScreen != null
    }

    override fun onLayoutComponentViewAdded(layoutComponentView: LayoutComponentView) {
        super.onLayoutComponentViewAdded(layoutComponentView)
        if (layoutComponentView.component.isScreen()) {
            layoutComponentView.view.addOnLayoutChangeListener(screenLayoutChangeListener)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (expandedScreen != null) {
            post {
                if (screenAnimator?.isRunning != true) {
                    applyExpandedScreenPlacements(animate = false)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        screenAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!areScreenGesturesEnabled || currentRuntimeLayout == null) {
            return super.dispatchTouchEvent(event)
        }

        return when (screenGestureDetector.onTouchEvent(event)) {
            ScreenGestureDetector.Result.PASS_THROUGH -> {
                val handled = super.dispatchTouchEvent(event)
                // If the first finger landed on an empty area, keep receiving this touch stream so that a second finger can
                // still start a gesture
                handled || (event.actionMasked == MotionEvent.ACTION_DOWN && screenGestureDetector.wantsTouchStream())
            }
            ScreenGestureDetector.Result.GESTURE_STARTED -> {
                // Release any touch that the emulated touchscreen (or other view) is currently holding
                val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancelEvent)
                cancelEvent.recycle()
                true
            }
            ScreenGestureDetector.Result.CONSUME -> true
        }
    }

    private fun updateInputs() {
        val currentRuntimeLayout = currentRuntimeLayout
        if (currentRuntimeLayout == null) {
            isGone = true
            return
        }

        isVisible = true
        val inputAlpha = currentRuntimeLayout.softInputOpacity / 100f

        val enableHapticFeedback = currentRuntimeLayout.isHapticFeedbackEnabled
        systemInputHandler?.let {
            getLayoutComponentView(LayoutComponent.DPAD)?.view?.setOnTouchListener(DpadInputHandler(it, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTONS)?.view?.setOnTouchListener(ButtonsInputHandler(it, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_L)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.L, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_R)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.R, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_SELECT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.SELECT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_START)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.START, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_HINGE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.HINGE, enableHapticFeedback, touchVibrator))
        }
        frontendInputHandler?.let {
            getLayoutComponentView(LayoutComponent.BUTTON_RESET)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.RESET, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_PAUSE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.PAUSE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.FAST_FORWARD, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_MICROPHONE_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.MICROPHONE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.TOGGLE_SOFT_INPUT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_SWAP_SCREENS)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.SWAP_SCREENS, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_QUICK_SAVE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.QUICK_SAVE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_QUICK_LOAD)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.QUICK_LOAD, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_REWIND)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.REWIND, enableHapticFeedback, touchVibrator))
        }

        getLayoutComponentViews().forEach {
            if (!it.component.isScreen()) {
                it.view.apply {
                    alpha = inputAlpha
                }
            }
        }

        updateScreenInputs()
    }

    private fun updateScreenInputs() {
        val (touchScreenComponent, nonTouchScreenComponent) = if (areScreensSwapped) {
            LayoutComponent.TOP_SCREEN to LayoutComponent.BOTTOM_SCREEN
        } else {
            LayoutComponent.BOTTOM_SCREEN to LayoutComponent.TOP_SCREEN
        }
        systemInputHandler?.let {
            getLayoutComponentView(touchScreenComponent)?.view?.setOnTouchListener(TouchscreenInputHandler(it))
        }
        getLayoutComponentView(nonTouchScreenComponent)?.view?.setOnTouchListener(null)
    }

    private fun isScreenGestureEligiblePoint(x: Float, y: Float): Boolean {
        // Find the top-most visible view under the point. Gestures can only start over screens or empty areas, never over
        // buttons, so regular gameplay input is never hijacked
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) continue

            if (x >= child.x && x < child.x + child.width && y >= child.y && y < child.y + child.height) {
                val component = getLayoutComponentViews().firstOrNull { it.view === child }?.component
                return component == null || component.isScreen()
            }
        }
        return true
    }

    private fun onPinchOutGesture(focusX: Float, focusY: Float) {
        val targetScreen = findScreenAt(focusX, focusY) ?: return
        if (expandedScreen == targetScreen) return

        expandScreen(targetScreen, animate = true)
        performGestureHapticFeedback()
    }

    private fun onPinchInGesture() {
        if (expandedScreen == null) return

        collapseScreens(animate = true)
        performGestureHapticFeedback()
    }

    private fun onTwoFingerSwipeGesture() {
        frontendInputHandler?.let {
            it.onKeyPress(Input.SWAP_SCREENS)
            it.onKeyReleased(Input.SWAP_SCREENS)
            performGestureHapticFeedback()
        }
    }

    private fun performGestureHapticFeedback() {
        if (currentRuntimeLayout?.isHapticFeedbackEnabled == true) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun getScreenViews(): List<LayoutComponentView> {
        return listOfNotNull(
            getLayoutComponentView(LayoutComponent.TOP_SCREEN),
            getLayoutComponentView(LayoutComponent.BOTTOM_SCREEN),
        )
    }

    /**
     * Returns the screen slot under the given point, preferring the one drawn on top. If no screen is under the point, the
     * closest one is returned.
     */
    private fun findScreenAt(x: Float, y: Float): LayoutComponent? {
        val screens = getScreenViews().sortedByDescending { indexOfChild(it.view) }
        screens.firstOrNull {
            val view = it.view
            x >= view.x && x < view.x + view.width && y >= view.y && y < view.y + view.height
        }?.let {
            return it.component
        }

        return screens.minByOrNull {
            val view = it.view
            hypot(view.x + view.width / 2f - x, view.y + view.height / 2f - y)
        }?.component
    }

    private fun expandScreen(screen: LayoutComponent, animate: Boolean) {
        if (getLayoutComponentView(screen) == null || width == 0 || height == 0) return

        if (expandedScreen == null) {
            saveScreenStates()
        }
        expandedScreen = screen
        applyExpandedScreenPlacements(animate)
    }

    private fun saveScreenStates() {
        savedScreenStates.clear()
        getScreenViews().forEach {
            savedScreenStates[it.component] = SavedScreenState(getCurrentPlacement(it), it.onTop)
        }
        originalLowerScreen = getScreenViews().minByOrNull { indexOfChild(it.view) }?.component
    }

    private fun applyExpandedScreenPlacements(animate: Boolean) {
        val expanded = expandedScreen ?: return
        val mainScreen = getLayoutComponentView(expanded) ?: return
        val secondaryScreen = getScreenViews().firstOrNull { it.component != expanded }
        if (width == 0 || height == 0) return

        val mainPlacement = fitAspectRatio(width, height, mainScreen.aspectRatio)
        val targets = mutableMapOf(expanded to mainPlacement)

        mainScreen.onTop = false
        if (secondaryScreen != null) {
            targets[secondaryScreen.component] = computePictureInPicturePlacement(mainPlacement, secondaryScreen.aspectRatio)
            secondaryScreen.onTop = true
            moveViewAbove(secondaryScreen.view, mainScreen.view)
        }

        animateScreensTo(targets, animate)
    }

    private fun collapseScreens(animate: Boolean) {
        if (expandedScreen == null) return
        expandedScreen = null

        val targets = savedScreenStates.mapValues { it.value.placement }
        savedScreenStates.forEach { (component, state) ->
            getLayoutComponentView(component)?.onTop = state.onTop
        }

        val lowerScreen = originalLowerScreen?.let { getLayoutComponentView(it) }
        val upperScreen = getScreenViews().firstOrNull { it.component != originalLowerScreen }
        if (lowerScreen != null && upperScreen != null) {
            moveViewAbove(upperScreen.view, lowerScreen.view)
        }

        savedScreenStates.clear()
        originalLowerScreen = null
        animateScreensTo(targets, animate)
    }

    private fun resetScreenExpansion() {
        screenAnimator?.cancel()
        screenAnimator = null
        expandedScreen = null
        savedScreenStates.clear()
        originalLowerScreen = null
    }

    private fun moveViewAbove(upper: View, lower: View) {
        val upperIndex = indexOfChild(upper)
        val lowerIndex = indexOfChild(lower)
        if (upperIndex < 0 || lowerIndex < 0 || upperIndex > lowerIndex) return

        // Screens are always kept below every other component, so this never moves a screen above a button
        removeView(upper)
        addView(upper, indexOfChild(lower) + 1)
    }

    private fun fitAspectRatio(areaWidth: Int, areaHeight: Int, aspectRatio: Float): ScreenPlacement {
        return if (areaWidth.toFloat() / areaHeight > aspectRatio) {
            val placementWidth = (areaHeight * aspectRatio).roundToInt()
            ScreenPlacement((areaWidth - placementWidth) / 2, 0, placementWidth, areaHeight)
        } else {
            val placementHeight = (areaWidth / aspectRatio).roundToInt()
            ScreenPlacement(0, (areaHeight - placementHeight) / 2, areaWidth, placementHeight)
        }
    }

    /**
     * Places the secondary screen in the corner where it covers the fewest buttons, preferring corners where it does not
     * cover the expanded screen either.
     */
    private fun computePictureInPicturePlacement(mainPlacement: ScreenPlacement, aspectRatio: Float): ScreenPlacement {
        val margin = (12 * resources.displayMetrics.density).roundToInt()
        val pipWidth = (min(width, height) * 0.32f).roundToInt()
        val pipHeight = (pipWidth / aspectRatio).roundToInt()

        val left = margin
        val right = width - pipWidth - margin
        val top = margin
        val bottom = height - pipHeight - margin
        val candidates = listOf(
            ScreenPlacement(right, top, pipWidth, pipHeight),
            ScreenPlacement(left, top, pipWidth, pipHeight),
            ScreenPlacement(right, bottom, pipWidth, pipHeight),
            ScreenPlacement(left, bottom, pipWidth, pipHeight),
        )

        val buttonPlacements = getLayoutComponentViews()
            .filter { !it.component.isScreen() && it.view.visibility == VISIBLE }
            .map { ScreenPlacement(it.view.x.toInt(), it.view.y.toInt(), it.view.width, it.view.height) }

        return candidates.minByOrNull { candidate ->
            val buttonOverlap = buttonPlacements.sumOf { overlapArea(candidate, it) }
            buttonOverlap + overlapArea(candidate, mainPlacement) / 2
        } ?: candidates.first()
    }

    private fun overlapArea(a: ScreenPlacement, b: ScreenPlacement): Long {
        val overlapWidth = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val overlapHeight = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        return if (overlapWidth > 0 && overlapHeight > 0) overlapWidth.toLong() * overlapHeight else 0L
    }

    private fun getCurrentPlacement(layoutComponentView: LayoutComponentView): ScreenPlacement {
        val params = layoutComponentView.view.layoutParams as FrameLayout.LayoutParams
        return ScreenPlacement(params.leftMargin, params.topMargin, params.width, params.height)
    }

    private fun animateScreensTo(targets: Map<LayoutComponent, ScreenPlacement>, animate: Boolean) {
        screenAnimator?.cancel()
        screenAnimator = null

        val startPlacements = targets.keys.mapNotNull { component ->
            getLayoutComponentView(component)?.let { component to getCurrentPlacement(it) }
        }.toMap()

        fun applyProgress(progress: Float) {
            targets.forEach { (component, end) ->
                val start = startPlacements[component] ?: return@forEach
                val view = getLayoutComponentView(component) ?: return@forEach
                view.setPositionAndSize(
                    Point(lerp(start.x, end.x, progress), lerp(start.y, end.y, progress)),
                    lerp(start.width, end.width, progress),
                    lerp(start.height, end.height, progress),
                )
            }
        }

        if (!animate) {
            applyProgress(1f)
            onScreenAreasChanged?.invoke()
            return
        }

        screenAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            start()
        }
        // Stacking order may have changed even if the first frame hasn't been laid out yet
        onScreenAreasChanged?.invoke()
    }

    private fun lerp(start: Int, end: Int, progress: Float): Int {
        return (start + (end - start) * progress).roundToInt()
    }

    private fun updateVisibility() {
        val currentConnectedControllersState = connectedControllersState
        var hiddenComponents = when(currentRuntimeLayout?.softInputBehaviour) {
            SoftInputBehaviour.ALWAYS_VISIBLE -> emptyList()
            SoftInputBehaviour.HIDE_SYSTEM_BUTTONS_WHEN_CONTROLLERS_CONNECTED, null -> {
                when(currentConnectedControllersState) {
                    ConnectedControllersState.NoControllers -> emptyList()
                    is ConnectedControllersState.ControllersConnected -> listOf(
                        LayoutComponent.BUTTONS,
                        LayoutComponent.DPAD,
                        LayoutComponent.BUTTON_L,
                        LayoutComponent.BUTTON_R,
                        LayoutComponent.BUTTON_START,
                        LayoutComponent.BUTTON_SELECT
                    )
                }
            }
            SoftInputBehaviour.HIDE_ALL_BUTTONS_ASSIGNED_TO_CONNECTED_CONTROLLERS -> when(currentConnectedControllersState) {
                ConnectedControllersState.NoControllers -> emptyList()
                is ConnectedControllersState.ControllersConnected -> {
                    LayoutComponent.entries.filter {
                        // The component can be hidden if all matching inputs are assigned to connected controllers
                        it.matchingInputs.all {
                            currentConnectedControllersState.assignedInputs.contains(it)
                        }
                    }
                }
            }
            SoftInputBehaviour.ALWAYS_INVISIBLE -> LayoutComponent.entries.toList()
        }

        if (!isSoftInputVisible) {
            // Hide everything except the soft input toggle button if it was not already hidden by the soft input behaviour logic
            hiddenComponents = if (hiddenComponents.contains(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT)) {
                LayoutComponent.entries.toList()
            } else {
                LayoutComponent.entries.toList().filter { it != LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT}
            }
        }

        val visibleComponents = LayoutComponent.entries.filter { !hiddenComponents.contains(it) }

        hiddenComponents.forEach { component ->
            if (!component.isScreen()) {
                getLayoutComponentView(component)?.view?.isVisible = false
            }
        }
        visibleComponents.forEach { component ->
            if (!component.isScreen()) {
                getLayoutComponentView(component)?.view?.isVisible = true
            }
        }
    }
}