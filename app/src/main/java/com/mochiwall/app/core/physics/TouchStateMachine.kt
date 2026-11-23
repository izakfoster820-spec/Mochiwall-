package com.mochiwall.app.core.physics

import kotlin.math.hypot

enum class TouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL
}

enum class TouchPhase {
    NONE,
    ARMED_TAP,
    DRAGGING
}

data class PendingPoke(
    val physX: Float,
    val physY: Float
)

/**
 * Unified single-pointer touch state machine shared by Stage 3 Sandbox and MochiWallpaperService.
 *
 * - ACTION_DOWN arms tap without firing a poke (zero accidental jolt at drag start).
 * - ACTION_MOVE past touchSlop enters DRAGGING with anchor locked at slop-exit (zero snap).
 * - Fast ACTION_UP (<= 300ms) inside touchSlop emits a single-consumption PendingPoke.
 * - ACTION_UP / ACTION_CANCEL from DRAGGING releases betaTether to 0 for elastic rebound.
 * - COMMAND_TAP fallback is deduplicated within 250ms of touch pokes or drag end.
 */
class TouchStateMachine {

    var phase: TouchPhase = TouchPhase.NONE
        private set

    var betaTether: Float = 0f
        private set

    var anchorPhysX: Float = 0f
        private set

    var anchorPhysY: Float = 0f
        private set

    var dragDx: Float = 0f
        private set

    var dragDy: Float = 0f
        private set

    private var activePointerId: Int = NO_POINTER
    private var screenDownX: Float = 0f
    private var screenDownY: Float = 0f
    private var physDownX: Float = 0f
    private var physDownY: Float = 0f
    private var downTimeMs: Long = 0L

    private var lastTouchPokeTimeMs: Long = UNSET_TIME_MS
    private var lastDragEndTimeMs: Long = UNSET_TIME_MS

    private var pendingPoke: PendingPoke? = null
    private var anchorChanged: Boolean = false

    fun onTouchEvent(
        action: TouchAction,
        pointerId: Int,
        screenX: Float,
        screenY: Float,
        physX: Float,
        physY: Float,
        eventTimeMs: Long,
        touchSlopPx: Float
    ) {
        // OS/launcher ACTION_CANCEL must always cancel the active gesture regardless of pointerId.
        if (action == TouchAction.CANCEL) {
            cancel(eventTimeMs)
            return
        }

        if (!screenX.isFinite() || !screenY.isFinite() || !physX.isFinite() || !physY.isFinite()) {
            return
        }

        when (action) {
            TouchAction.DOWN -> {
                if (phase != TouchPhase.NONE) {
                    // Single active pointer policy: ignore secondary pointers.
                    return
                }
                activePointerId = pointerId
                phase = TouchPhase.ARMED_TAP
                screenDownX = screenX
                screenDownY = screenY
                physDownX = physX
                physDownY = physY
                downTimeMs = eventTimeMs
                betaTether = 0f
                dragDx = 0f
                dragDy = 0f
            }

            TouchAction.MOVE -> {
                if (pointerId != activePointerId || phase == TouchPhase.NONE) return

                if (phase == TouchPhase.ARMED_TAP) {
                    val slop = if (touchSlopPx.isFinite() && touchSlopPx >= 0f) touchSlopPx else 0f
                    val distPx = hypot(screenX - screenDownX, screenY - screenDownY)
                    if (distPx > slop) {
                        phase = TouchPhase.DRAGGING
                        anchorPhysX = physX
                        anchorPhysY = physY
                        dragDx = 0f
                        dragDy = 0f
                        betaTether = 0f
                        anchorChanged = true
                    }
                } else if (phase == TouchPhase.DRAGGING) {
                    val rawDx = physX - anchorPhysX
                    val rawDy = physY - anchorPhysY
                    val len = hypot(rawDx, rawDy)
                    if (len > MAX_DRAG_DISTANCE_GU) {
                        val scale = MAX_DRAG_DISTANCE_GU / len
                        dragDx = rawDx * scale
                        dragDy = rawDy * scale
                    } else {
                        dragDx = rawDx
                        dragDy = rawDy
                    }
                }
            }

            TouchAction.UP -> {
                if (pointerId != activePointerId) return

                if (phase == TouchPhase.ARMED_TAP) {
                    val elapsed = eventTimeMs - downTimeMs
                    if (elapsed in 0L..TAP_MAX_DURATION_MS) {
                        pendingPoke = PendingPoke(physDownX, physDownY)
                        lastTouchPokeTimeMs = eventTimeMs
                    }
                } else if (phase == TouchPhase.DRAGGING) {
                    lastDragEndTimeMs = eventTimeMs
                }

                clearActiveGesture()
            }

            TouchAction.CANCEL -> {
                // Handled unconditionally above.
            }
        }
    }

    /**
     * Enqueues a WallpaperManager.COMMAND_TAP fallback poke unless deduplicated by an active drag,
     * a recent touch poke (< 250 ms), or a recent drag end (< 250 ms).
     */
    fun enqueueCommandTap(physX: Float, physY: Float, uptimeMs: Long): Boolean {
        if (!physX.isFinite() || !physY.isFinite()) return false
        if (phase == TouchPhase.DRAGGING) return false
        if (uptimeMs - lastTouchPokeTimeMs < COMMAND_TAP_DEDUP_MS) return false
        if (uptimeMs - lastDragEndTimeMs < COMMAND_TAP_DEDUP_MS) return false

        pendingPoke = PendingPoke(physX, physY)
        return true
    }

    fun consumePendingPoke(): PendingPoke? {
        val poke = pendingPoke
        pendingPoke = null
        return poke
    }

    fun hasPendingPoke(): Boolean = pendingPoke != null

    fun consumeAnchorChanged(): Boolean {
        val changed = anchorChanged
        anchorChanged = false
        return changed
    }

    fun advanceRamp(dtSec: Float) {
        if (phase == TouchPhase.DRAGGING) {
            if (dtSec.isFinite() && dtSec > 0f) {
                betaTether = (betaTether + dtSec / TETHER_RAMP_SEC).coerceIn(0f, 1f)
            }
        } else {
            betaTether = 0f
        }
    }

    fun cancel(uptimeMs: Long) {
        if (phase == TouchPhase.DRAGGING) {
            lastDragEndTimeMs = uptimeMs
        }
        clearActiveGesture()
    }

    fun reset() {
        clearActiveGesture()
        pendingPoke = null
        anchorChanged = false
        lastTouchPokeTimeMs = UNSET_TIME_MS
        lastDragEndTimeMs = UNSET_TIME_MS
    }

    private fun clearActiveGesture() {
        phase = TouchPhase.NONE
        activePointerId = NO_POINTER
        betaTether = 0f
        dragDx = 0f
        dragDy = 0f
    }

    companion object {
        const val TAP_MAX_DURATION_MS = 300L
        const val COMMAND_TAP_DEDUP_MS = 250L
        const val TETHER_RAMP_SEC = 0.040f
        const val MAX_DRAG_DISTANCE_GU = 0.35f
        private const val NO_POINTER = -1
        private const val UNSET_TIME_MS = Long.MIN_VALUE / 4
    }
}
