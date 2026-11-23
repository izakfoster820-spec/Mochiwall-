package com.mochiwall.app.core.physics

import com.mochiwall.app.core.model.DerivedPhysicsParams
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.PhysicsStyle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 36x80 Structure-of-Arrays (SoA) 60 Hz fixed-timestep elastic mochi solver with:
 * - Zero per-frame heap allocations (cached DerivedPhysicsParams + pre-allocated viscosity scratch)
 * - prevU/currU render interpolation for smooth 90/120 Hz LTPO display output
 * - Two-pass Semi-Implicit Euler + symmetric Laplacian neighbor velocity viscosity (MOCHI anti-ripple)
 *   + alternating Gauss-Seidel commit
 * - Anisotropic cardinal edge clamp (1.65 hx, 1.65 hy)
 * - Closed-form 6-incident-triangle minimum-area anti-inversion guard (A_min = 0.20 A0)
 * - Cohesive Mochi bounce poke & Radial Poisson Jelly poke, Wake Wobble impulse, and exact IDLE sleep reset
 */
class MochiPhysicsSolver(initialAspectRatio: Float = DEFAULT_ASPECT_RATIO) {

    val p0x = FloatArray(VERTEX_COUNT)
    val p0y = FloatArray(VERTEX_COUNT)
    val ux = FloatArray(VERTEX_COUNT)
    val uy = FloatArray(VERTEX_COUNT)
    val prevUx = FloatArray(VERTEX_COUNT)
    val prevUy = FloatArray(VERTEX_COUNT)
    val vx = FloatArray(VERTEX_COUNT)
    val vy = FloatArray(VERTEX_COUNT)
    val weights = FloatArray(VERTEX_COUNT)
    val mobility = FloatArray(VERTEX_COUNT)
    val dampScale = FloatArray(VERTEX_COUNT)
    val tetherWeight = FloatArray(VERTEX_COUNT)

    private val snapVx = FloatArray(VERTEX_COUNT)
    private val snapVy = FloatArray(VERTEX_COUNT)

    var renderAlpha: Float = 1f
        private set

    var isIdle: Boolean = true
        private set

    var hasActiveVertices: Boolean = false
        private set

    var aspectRatio: Float = DEFAULT_ASPECT_RATIO
        private set

    var hx: Float = 1f / (NX - 1).toFloat()
        private set

    var hy: Float = DEFAULT_ASPECT_RATIO / (NY - 1).toFloat()
        private set

    private var restTwiceArea: Float = hx * hy
    private var minTwiceArea: Float = MIN_AREA_RATIO * restTwiceArea
    private var accumulator: Double = 0.0
    private var lastFrameTimeNs: Long = 0L
    private var reverseSweep: Boolean = false

    private var lastConfig: PhysicsConfig? = null
    private var cachedParams: DerivedPhysicsParams? = null

    init {
        setAspectRatio(initialAspectRatio)
    }

    private fun resolveParams(config: PhysicsConfig): DerivedPhysicsParams {
        val cached = cachedParams
        if (cached != null && config == lastConfig) {
            return cached
        }
        val derived = config.deriveParams()
        lastConfig = config
        cachedParams = derived
        return derived
    }

    fun interpolatedUx(index: Int): Float {
        val prev = prevUx[index]
        return prev + renderAlpha * (ux[index] - prev)
    }

    fun interpolatedUy(index: Int): Float {
        val prev = prevUy[index]
        return prev + renderAlpha * (uy[index] - prev)
    }

    fun setAspectRatio(newAspectRatio: Float) {
        val safeAspect = if (newAspectRatio.isFinite() && newAspectRatio > 1e-4f) {
            newAspectRatio
        } else {
            DEFAULT_ASPECT_RATIO
        }
        aspectRatio = safeAspect
        hx = 1f / (NX - 1).toFloat()
        hy = safeAspect / (NY - 1).toFloat()
        restTwiceArea = hx * hy
        minTwiceArea = MIN_AREA_RATIO * restTwiceArea

        for (j in 0 until NY) {
            val y = j * hy
            val rowOffset = j * NX
            for (i in 0 until NX) {
                val idx = rowOffset + i
                p0x[idx] = i * hx
                p0y[idx] = y
            }
        }
    }

    fun setMask(newWeights: FloatArray, hasActive: Boolean = true) {
        require(newWeights.size == VERTEX_COUNT) {
            "Mask weights must have exactly $VERTEX_COUNT elements"
        }

        var anyActive = false
        for (j in 0 until NY) {
            val isBorderY = (j == 0 || j == NY - 1)
            val rowOffset = j * NX
            for (i in 0 until NX) {
                val idx = rowOffset + i
                val raw = newWeights[idx]
                val w = if (isBorderY || i == 0 || i == NX - 1 || !raw.isFinite()) {
                    0f
                } else {
                    raw.coerceIn(0f, 1f)
                }

                weights[idx] = w
                if (w > 0f) {
                    val wd = w.toDouble()
                    mobility[idx] = wd.pow(1.3).toFloat()
                    dampScale[idx] = wd.pow(0.65).toFloat()
                    anyActive = true
                } else {
                    mobility[idx] = 0f
                    dampScale[idx] = 0f
                    ux[idx] = 0f
                    uy[idx] = 0f
                    prevUx[idx] = 0f
                    prevUy[idx] = 0f
                    vx[idx] = 0f
                    vy[idx] = 0f
                }
            }
        }

        hasActiveVertices = hasActive && anyActive
        if (!hasActiveVertices) {
            resetPose()
        }
    }

    fun updateTetherWeights(anchorX: Float, anchorY: Float, sigmaRadius: Float) {
        if (!anchorX.isFinite() || !anchorY.isFinite()) return
        val safeSigma = if (sigmaRadius.isFinite() && sigmaRadius > 1e-4f) sigmaRadius else 0.16f
        val invTwoSigmaSq = 1.0 / (2.0 * safeSigma.toDouble() * safeSigma.toDouble())

        for (idx in 0 until VERTEX_COUNT) {
            val dx = (p0x[idx] - anchorX).toDouble()
            val dy = (p0y[idx] - anchorY).toDouble()
            tetherWeight[idx] = exp(-(dx * dx + dy * dy) * invTwoSigmaSq).toFloat()
        }
    }

    fun applyPoke(tapX: Float, tapY: Float, config: PhysicsConfig) {
        if (!hasActiveVertices || !tapX.isFinite() || !tapY.isFinite()) return

        val params = resolveParams(config)
        val iPoke = params.iPoke

        if (config.style == PhysicsStyle.JELLY) {
            val sigmaTap = params.sigmaTap
            val invSigmaTap = 1f / sigmaTap
            val invTwoSigmaTapSq = 1.0 / (2.0 * sigmaTap.toDouble() * sigmaTap.toDouble())

            for (idx in 0 until VERTEX_COUNT) {
                val m = mobility[idx]
                if (m <= 0f) continue

                val rx = p0x[idx] - tapX
                val ry = p0y[idx] - tapY
                val distSq = rx.toDouble() * rx.toDouble() + ry.toDouble() * ry.toDouble()
                val g = exp(-distSq * invTwoSigmaTapSq).toFloat()

                var dvx = m * iPoke * (rx * invSigmaTap) * g
                var dvy = m * iPoke * (ry * invSigmaTap + 0.25f) * g

                val impulseMag = hypot(dvx, dvy)
                if (impulseMag > MAX_POKE_IMPULSE) {
                    val scale = MAX_POKE_IMPULSE / impulseMag
                    dvx *= scale
                    dvy *= scale
                }

                var nvx = vx[idx] + dvx
                var nvy = vy[idx] + dvy
                val velMag = hypot(nvx, nvy)
                if (velMag > MAX_VELOCITY) {
                    val scale = MAX_VELOCITY / velMag
                    nvx *= scale
                    nvy *= scale
                }
                if (nvx.isFinite() && nvy.isFinite()) {
                    vx[idx] = nvx
                    vy[idx] = nvy
                }
            }
        } else {
            val sigma = 1.15f * params.sigmaRadius
            val invTwoSigmaSq = 1.0 / (2.0 * sigma.toDouble() * sigma.toDouble())

            for (idx in 0 until VERTEX_COUNT) {
                val m = mobility[idx]
                if (m <= 0f) continue

                val rx = p0x[idx] - tapX
                val ry = p0y[idx] - tapY
                val distSq = rx.toDouble() * rx.toDouble() + ry.toDouble() * ry.toDouble()
                val dist = hypot(rx, ry)
                val radialDirX = if (dist > 1e-5f) rx / dist else 0f
                val radialDirY = if (dist > 1e-5f) ry / dist else 0f

                val gaussian = exp(-distSq * invTwoSigmaSq).toFloat()
                val impulseScale = m * gaussian * iPoke * 0.70f

                var dvx = impulseScale * (0.25f * radialDirX)
                var dvy = impulseScale * (0.85f + 0.25f * radialDirY)

                val impulseMag = hypot(dvx, dvy)
                if (impulseMag > MAX_POKE_IMPULSE) {
                    val scale = MAX_POKE_IMPULSE / impulseMag
                    dvx *= scale
                    dvy *= scale
                }

                var nvx = vx[idx] + dvx
                var nvy = vy[idx] + dvy
                val velMag = hypot(nvx, nvy)
                if (velMag > MAX_VELOCITY) {
                    val scale = MAX_VELOCITY / velMag
                    nvx *= scale
                    nvy *= scale
                }
                if (nvx.isFinite() && nvy.isFinite()) {
                    vx[idx] = nvx
                    vy[idx] = nvy
                }
            }
        }

        wakeFromIdle()
    }

    fun applyWakeWobble(config: PhysicsConfig) {
        if (!hasActiveVertices) return

        val clean = config.sanitized()
        val scale = 0.75f + 0.50f * clean.softness
        val nudgeVx = WAKE_WOBBLE_BASE_VX * scale
        val nudgeVy = WAKE_WOBBLE_BASE_VY * scale

        for (idx in 0 until VERTEX_COUNT) {
            val m = mobility[idx]
            if (m <= 0f) continue

            var nvx = vx[idx] + m * nudgeVx
            var nvy = vy[idx] + m * nudgeVy
            val velMag = hypot(nvx, nvy)
            if (velMag > MAX_VELOCITY) {
                val velScale = MAX_VELOCITY / velMag
                nvx *= velScale
                nvy *= velScale
            }
            if (nvx.isFinite() && nvy.isFinite()) {
                vx[idx] = nvx
                vy[idx] = nvy
            }
        }

        wakeFromIdle()
    }

    fun resetPose() {
        ux.fill(0f)
        uy.fill(0f)
        prevUx.fill(0f)
        prevUy.fill(0f)
        vx.fill(0f)
        vy.fill(0f)
        renderAlpha = 1f
        accumulator = 0.0
        lastFrameTimeNs = 0L
        isIdle = true
    }

    /**
     * Advances the simulation for a render frame at [frameTimeNs].
     * Returns `true` if motion is still active and another frame should be scheduled,
     * or `false` if the solver has settled cleanly into IDLE sleep.
     */
    fun stepFrame(
        frameTimeNs: Long,
        config: PhysicsConfig,
        touch: TouchStateMachine,
        sensorFx: Float,
        sensorFy: Float
    ): Boolean {
        val safeFx = if (sensorFx.isFinite()) sensorFx else 0f
        val safeFy = if (sensorFy.isFinite()) sensorFy else 0f

        if (config.touchEnabled) {
            val poke = touch.consumePendingPoke()
            if (poke != null) {
                applyPoke(poke.physX, poke.physY, config)
            }
            if (touch.consumeAnchorChanged()) {
                updateTetherWeights(touch.anchorPhysX, touch.anchorPhysY, resolveParams(config).sigmaRadius)
            }
        } else {
            touch.consumePendingPoke()
            touch.consumeAnchorChanged()
        }

        if (!hasActiveVertices) {
            resetPose()
            return false
        }

        val effectiveTouchPhase = if (config.touchEnabled) touch.phase else TouchPhase.NONE
        val hasExternalInput = (effectiveTouchPhase == TouchPhase.DRAGGING) ||
            (safeFx != 0f) || (safeFy != 0f)

        if (isIdle && !hasExternalInput) {
            return false
        }

        if (isIdle) {
            wakeFromIdle()
        }

        // 1. Duplicate / non-monotonic timestamp guard: do not advance or mutate state
        if (lastFrameTimeNs != 0L && frameTimeNs <= lastFrameTimeNs) {
            return !isIdle
        }

        // 2. Wake-from-IDLE branch: run 1 clean substep and prime accumulator = FIXED_DT
        // so 90/120 Hz frames progress strictly monotonically (u1 -> lerp(u1, u2, alpha) -> u2).
        if (lastFrameTimeNs == 0L) {
            lastFrameTimeNs = frameTimeNs
            if (config.touchEnabled) {
                touch.advanceRamp(FIXED_DT_F)
            }
            stepSubstep(
                config = config,
                touchPhase = effectiveTouchPhase,
                dragDx = touch.dragDx,
                dragDy = touch.dragDy,
                betaTether = if (config.touchEnabled) touch.betaTether else 0f,
                sensorFx = safeFx,
                sensorFy = safeFy
            )
            accumulator = FIXED_DT
            renderAlpha = 1f
        } else {
            val rawDt = (frameTimeNs - lastFrameTimeNs) * 1e-9
            lastFrameTimeNs = frameTimeNs
            val clampedDt = rawDt.coerceIn(0.0, MAX_FRAME_DT)
            accumulator += clampedDt

            var substeps = 0
            while (accumulator >= FIXED_DT && substeps < MAX_SUBSTEPS) {
                if (config.touchEnabled) {
                    touch.advanceRamp(FIXED_DT_F)
                }
                stepSubstep(
                    config = config,
                    touchPhase = effectiveTouchPhase,
                    dragDx = touch.dragDx,
                    dragDy = touch.dragDy,
                    betaTether = if (config.touchEnabled) touch.betaTether else 0f,
                    sensorFx = safeFx,
                    sensorFy = safeFy
                )
                accumulator -= FIXED_DT
                substeps++
            }
            if (substeps == MAX_SUBSTEPS && accumulator > FIXED_DT) {
                accumulator = FIXED_DT
            }
            renderAlpha = (accumulator / FIXED_DT).toFloat().coerceIn(0f, 1f)
        }

        if (effectiveTouchPhase == TouchPhase.NONE && safeFx == 0f && safeFy == 0f) {
            var maxAbsU = 0f
            var maxAbsV = 0f
            for (idx in 0 until VERTEX_COUNT) {
                if (mobility[idx] <= 0f) continue
                val au = max(abs(ux[idx]), abs(uy[idx]))
                val av = max(abs(vx[idx]), abs(vy[idx]))
                if (au > maxAbsU) maxAbsU = au
                if (av > maxAbsV) maxAbsV = av
            }
            if (maxAbsU < IDLE_POS_EPS && maxAbsV < IDLE_VEL_EPS) {
                resetPose()
                return false
            }
        }

        return true
    }

    /**
     * Executes a single fixed 60 Hz physics substep.
     * Snapshots prevUx/prevUy before advancing ux/uy so render interpolation stays exact.
     */
    fun stepSubstep(
        config: PhysicsConfig,
        touchPhase: TouchPhase,
        dragDx: Float,
        dragDy: Float,
        betaTether: Float,
        sensorFx: Float,
        sensorFy: Float
    ) {
        System.arraycopy(ux, 0, prevUx, 0, VERTEX_COUNT)
        System.arraycopy(uy, 0, prevUy, 0, VERTEX_COUNT)

        if (!hasActiveVertices) return

        val params = resolveParams(config)
        val kStiff = params.kStiff
        val kCoupleOver5 = params.kCouple * 0.2f
        val cShear = params.cShear
        val cDamp = params.cDamp
        val kGrab = params.kGrab
        val cGrab = params.cGrab

        val safeSensorFx = if (sensorFx.isFinite()) sensorFx else 0f
        val safeSensorFy = if (sensorFy.isFinite()) sensorFy else 0f
        val isDragging = touchPhase == TouchPhase.DRAGGING && betaTether.isFinite() && betaTether > 0f
        val safeBeta = if (isDragging) betaTether.coerceIn(0f, 1f) else 0f
        val safeDragDx = if (dragDx.isFinite()) dragDx else 0f
        val safeDragDy = if (dragDy.isFinite()) dragDy else 0f

        // Pass 1: Velocity Update with Symmetric Anti-Ripple Viscosity & Implicit Damping
        if (cShear > 0f) {
            System.arraycopy(vx, 0, snapVx, 0, VERTEX_COUNT)
            System.arraycopy(vy, 0, snapVy, 0, VERTEX_COUNT)
        }

        for (j in 1 until NY - 1) {
            val rowOffset = j * NX
            for (i in 1 until NX - 1) {
                val idx = rowOffset + i
                val m = mobility[idx]
                if (m <= 0f) continue

                val s = dampScale[idx]
                val uxi = ux[idx]
                val uyi = uy[idx]

                val orthoX = (ux[idx - 1] + ux[idx + 1] + ux[idx - NX] + ux[idx + NX]) - 4f * uxi
                val orthoY = (uy[idx - 1] + uy[idx + 1] + uy[idx - NX] + uy[idx + NX]) - 4f * uyi

                val diagX = (ux[idx - NX - 1] + ux[idx - NX + 1] + ux[idx + NX - 1] + ux[idx + NX + 1]) - 4f * uxi
                val diagY = (uy[idx - NX - 1] + uy[idx - NX + 1] + uy[idx + NX - 1] + uy[idx + NX + 1]) - 4f * uyi

                val fCoupleX = kCoupleOver5 * (orthoX + 0.25f * diagX)
                val fCoupleY = kCoupleOver5 * (orthoY + 0.25f * diagY)

                val fTetherX: Float
                val fTetherY: Float
                val cEff: Float
                if (isDragging) {
                    val bg = safeBeta * tetherWeight[idx]
                    fTetherX = bg * kGrab * (safeDragDx - uxi)
                    fTetherY = bg * kGrab * (safeDragDy - uyi)
                    cEff = s * (cDamp + bg * cGrab)
                } else {
                    fTetherX = 0f
                    fTetherY = 0f
                    cEff = s * cDamp
                }

                var ax = m * (-kStiff * uxi + fCoupleX + safeSensorFx + fTetherX)
                var ay = m * (-kStiff * uyi + fCoupleY + safeSensorFy + fTetherY)

                if (cShear > 0f) {
                    val viscX = cShear * (
                        (snapVx[idx - 1] + snapVx[idx + 1] + snapVx[idx - NX] + snapVx[idx + NX]) -
                            4f * snapVx[idx]
                        )
                    val viscY = cShear * (
                        (snapVy[idx - 1] + snapVy[idx + 1] + snapVy[idx - NX] + snapVy[idx + NX]) -
                            4f * snapVy[idx]
                        )
                    ax += viscX
                    ay += viscY
                }

                val invDenom = 1f / (1f + FIXED_DT_F * cEff)
                var nvx = (vx[idx] + FIXED_DT_F * ax) * invDenom
                var nvy = (vy[idx] + FIXED_DT_F * ay) * invDenom

                val velMag = hypot(nvx, nvy)
                if (velMag > MAX_VELOCITY) {
                    val scale = MAX_VELOCITY / velMag
                    nvx *= scale
                    nvy *= scale
                }

                if (nvx.isFinite() && nvy.isFinite()) {
                    vx[idx] = nvx
                    vy[idx] = nvy
                } else {
                    vx[idx] = 0f
                    vy[idx] = 0f
                }
            }
        }

        // Pass 2: Alternating Row-Major / Reverse Gauss-Seidel Commit + Edge Clamp + 6-Triangle Area Guard
        val maxEdgeX = EDGE_CLAMP_FACTOR * hx
        val maxEdgeY = EDGE_CLAMP_FACTOR * hy

        if (!reverseSweep) {
            for (j in 1 until NY - 1) {
                val rowOffset = j * NX
                for (i in 1 until NX - 1) {
                    commitVertex(rowOffset + i, maxEdgeX, maxEdgeY)
                }
            }
        } else {
            for (j in NY - 2 downTo 1) {
                val rowOffset = j * NX
                for (i in NX - 2 downTo 1) {
                    commitVertex(rowOffset + i, maxEdgeX, maxEdgeY)
                }
            }
        }
        reverseSweep = !reverseSweep
    }

    private fun commitVertex(idx: Int, maxEdgeX: Float, maxEdgeY: Float) {
        if (mobility[idx] <= 0f) return

        val xOld = p0x[idx] + ux[idx]
        val yOld = p0y[idx] + uy[idx]
        var xCand = xOld + FIXED_DT_F * vx[idx]
        var yCand = yOld + FIXED_DT_F * vy[idx]

        // Anisotropic cardinal edge clamp: L_max_x = 1.65 hx, L_max_y = 1.65 hy
        val idxL = idx - 1
        val idxR = idx + 1
        val xL = p0x[idxL] + ux[idxL]
        val xR = p0x[idxR] + ux[idxR]
        val loX = max(xL, xR) - maxEdgeX
        val hiX = min(xL, xR) + maxEdgeX
        xCand = if (loX <= hiX) xCand.coerceIn(loX, hiX) else 0.5f * (loX + hiX)

        val idxT = idx - NX
        val idxB = idx + NX
        val yT = p0y[idxT] + uy[idxT]
        val yB = p0y[idxB] + uy[idxB]
        val loY = max(yT, yB) - maxEdgeY
        val hiY = min(yT, yB) + maxEdgeY
        yCand = if (loY <= hiY) yCand.coerceIn(loY, hiY) else 0.5f * (loY + hiY)

        // Closed-form minimum-area projection on the 6 incident triangles around V
        // Triangulation diagonal: TL (i,j) -> BR (i+1,j+1) in (+X right, +Y down) coordinates
        // Cyclic star neighbors: n0=(i-1,j-1), n1=(i,j-1), n2=(i+1,j), n3=(i+1,j+1), n4=(i,j+1), n5=(i-1,j)
        val n0 = idx - NX - 1
        val n1 = idx - NX
        val n2 = idx + 1
        val n3 = idx + NX + 1
        val n4 = idx + NX
        val n5 = idx - 1

        var tSafe = 1f
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n0, n1)
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n1, n2)
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n2, n3)
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n3, n4)
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n4, n5)
        tSafe = constrainTriangleArea(tSafe, xOld, yOld, xCand, yCand, n5, n0)

        val xNew = xOld + tSafe * (xCand - xOld)
        val yNew = yOld + tSafe * (yCand - yOld)

        val newUx = xNew - p0x[idx]
        val newUy = yNew - p0y[idx]

        if (newUx.isFinite() && newUy.isFinite()) {
            ux[idx] = newUx
            uy[idx] = newUy
        } else {
            ux[idx] = 0f
            uy[idx] = 0f
            vx[idx] = 0f
            vy[idx] = 0f
        }
    }

    private fun constrainTriangleArea(
        currentTSafe: Float,
        xOld: Float,
        yOld: Float,
        xCand: Float,
        yCand: Float,
        bIdx: Int,
        cIdx: Int
    ): Float {
        val bx = p0x[bIdx] + ux[bIdx]
        val by = p0y[bIdx] + uy[bIdx]
        val cx = p0x[cIdx] + ux[cIdx]
        val cy = p0y[cIdx] + uy[cIdx]

        // Positive signed twice-area for (V, B, C) in +X right, +Y down space
        val twoArea0 = (bx - xOld) * (cy - yOld) - (by - yOld) * (cx - xOld)
        val twoArea1 = (bx - xCand) * (cy - yCand) - (by - yCand) * (cx - xCand)

        // Strictly gate on shrinking moves so vertices never lock up when recovering
        if (twoArea1 < minTwiceArea && twoArea1 < twoArea0) {
            val denom = twoArea0 - twoArea1
            val tLimit = if (denom > 1e-12f) {
                ((twoArea0 - minTwiceArea) / denom).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (tLimit < currentTSafe) return tLimit
        }
        return currentTSafe
    }

    private fun wakeFromIdle() {
        if (isIdle) {
            isIdle = false
            lastFrameTimeNs = 0L
            accumulator = 0.0
        }
    }

    companion object {
        const val NX = 36
        const val NY = 80
        const val VERTEX_COUNT = NX * NY
        const val TRIANGLE_COUNT = (NX - 1) * (NY - 1) * 2 // 5,530 triangles

        const val FIXED_DT = 1.0 / 60.0
        const val FIXED_DT_F = (1.0 / 60.0).toFloat()
        const val MAX_SUBSTEPS = 3
        const val MAX_FRAME_DT = MAX_SUBSTEPS * FIXED_DT

        const val MAX_VELOCITY = 8.0f
        const val MAX_POKE_IMPULSE = 4.5f
        const val EDGE_CLAMP_FACTOR = 1.65f
        const val MIN_AREA_RATIO = 0.20f

        const val IDLE_POS_EPS = 1e-4f
        const val IDLE_VEL_EPS = 1e-3f

        const val WAKE_WOBBLE_BASE_VX = 0.18f
        const val WAKE_WOBBLE_BASE_VY = 0.42f
        const val DEFAULT_ASPECT_RATIO = 2808f / 1256f
    }
}
