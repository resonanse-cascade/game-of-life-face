package com.resonanse.golwatchface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * GAME OF LIFE — a Wear OS watch face where the background is a live Conway board.
 *
 * How the two halves fit together:
 *
 *  - The board is a 64x64 torus stepped by [LifeEngine] with word-parallel bitwise
 *    arithmetic, and painted by handing a 64x64 pixel buffer to one `drawBitmap`
 *    with filtering off. So the entire background — 4096 cells — costs one texture
 *    upload and one blit per frame, not 4096 draw calls.
 *
 *  - The time is stamped onto that same lattice with [PixelFont] and then held
 *    permanently dead, together with a one-cell halo and the boxes the small type
 *    and complications occupy. That mask is what keeps the face readable: without
 *    it the numerals are unreadable inside two seconds of churn.
 *
 *  - When the minute rolls over, the shell around the outgoing numerals is turned
 *    loose into the board as live cells, so the old time visibly disperses into the
 *    simulation instead of just being replaced.
 *
 *  - A soup always decays into still lifes and short oscillators, which on a watch
 *    face means a frozen picture. [LifeEngine] hashes each generation and, when the
 *    board repeats itself or nearly dies out, a disc of fresh soup is dropped in.
 *
 * Tap anywhere off a complication to drop an R-pentomino there.
 * Long-press → Customize for theme, rule, and motion.
 */
class LifeWatchFaceService : WatchFaceService() {

    companion object {
        const val TOP_ID = 100
        const val BL_ID  = 101
        const val BR_ID  = 102

        // Layout, in screen fractions. The bottom pair sits at y≈0.8 where the
        // circle is still ~0.72 wide, so both slots clear the bezel.
        private val TOP_BOUNDS = RectF(0.30f, 0.085f, 0.70f, 0.185f)
        private val BL_BOUNDS  = RectF(0.155f, 0.745f, 0.455f, 0.845f)
        private val BR_BOUNDS  = RectF(0.545f, 0.745f, 0.845f, 0.845f)
        private const val READOUT_Y = 0.690f     // "GEN nnnnnn  POP nnnn"
        private const val DIGIT_SCALE = 2        // 5x7 font -> 10x14 cells per digit

        /** Frame interval and generation interval, in ms, per motion setting. */
        data class Motion(val id: String, val label: String, val frameMs: Long, val genMs: Long)

        // One frame per generation, deliberately: the only thing that changes between
        // two frames of the same generation is the seconds arc, so drawing 2-4 frames
        // per step -- as these presets originally did -- repaints an identical board
        // for no visible gain. Since this display never shows the face in ambient,
        // frames drawn during a glance are the face's entire power cost, and this is
        // the lever that matters.
        val MOTIONS = listOf(
            Motion("fast",   "Fast",     66L,   66L),   // 15 fps, 15 gen/s
            Motion("normal", "Normal",  200L,  200L),   //  5 fps,  5 gen/s
            Motion("calm",   "Calm",    500L,  500L),   //  2 fps,  2 gen/s
            // Still: the board only advances when the minute changes, so the face is
            // a static picture that redraws once a second for the seconds arc.
            Motion("still",  "Still",  1000L, Long.MAX_VALUE),
        )

        fun motionFor(id: String?) = MOTIONS.firstOrNull { it.id == id } ?: MOTIONS[1]

        private fun listSetting(
            id: String, name: String, desc: String,
            options: List<Pair<String, String>>, defaultIndex: Int = 0
        ): ListUserStyleSetting {
            val opts = options.map { (oid, label) ->
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(oid), label, label, null
                )
            }
            return ListUserStyleSetting(
                UserStyleSetting.Id(id), name, desc, null, opts,
                listOf(
                    WatchFaceLayer.BASE,
                    WatchFaceLayer.COMPLICATIONS,
                    WatchFaceLayer.COMPLICATIONS_OVERLAY
                ),
                // Without this the framework defaults to options[0], which for Motion
                // is the fastest setting -- a poor thing to hand someone by default.
                opts[defaultIndex]
            )
        }

        // Held as single instances so the schema and the renderer agree on identity.
        val THEME_SETTING = listSetting(
            "theme", "Theme", "Phosphor colour",
            Theme.ALL.map { it.id to it.label })

        val RULE_SETTING = listSetting(
            "rule", "Rule", "Cellular automaton",
            LifeEngine.RULES.map { it.id to it.label })

        val MOTION_SETTING = listSetting(
            "motion", "Motion", "Speed against battery",
            MOTIONS.map { it.id to it.label },
            defaultIndex = MOTIONS.indexOfFirst { it.id == "normal" })

        fun optionId(repo: CurrentUserStyleRepository, setting: ListUserStyleSetting): String? {
            val opt = repo.userStyle.value[setting] as? ListUserStyleSetting.ListOption
            return opt?.id?.value?.let { String(it) }
        }
    }

    override fun createUserStyleSchema() =
        UserStyleSchema(listOf(THEME_SETTING, RULE_SETTING, MOTION_SETTING))

    private val slotDrawables = mutableListOf<ComplicationDrawable>()

    override fun createComplicationSlotsManager(
        repo: CurrentUserStyleRepository
    ): ComplicationSlotsManager = ComplicationSlotsManager(
        listOf(
            mkSlot(TOP_ID, TOP_BOUNDS, SystemDataSources.DATA_SOURCE_DATE),
            mkSlot(BL_ID,  BL_BOUNDS,  SystemDataSources.DATA_SOURCE_WATCH_BATTERY),
            mkSlot(BR_ID,  BR_BOUNDS,  SystemDataSources.DATA_SOURCE_STEP_COUNT),
        ),
        repo
    )

    private fun mkSlot(id: Int, bounds: RectF, src: Int): ComplicationSlot {
        val drawable = ComplicationDrawable(applicationContext).apply {
            activeStyle.backgroundColor = Color.TRANSPARENT
            activeStyle.borderColor = Color.TRANSPARENT
            ambientStyle.backgroundColor = Color.TRANSPARENT
            ambientStyle.borderColor = Color.TRANSPARENT
        }
        slotDrawables += drawable
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
            id = id,
            canvasComplicationFactory = { ws, listener ->
                CanvasComplicationDrawable(drawable, ws, listener)
            },
            supportedTypes = listOf(
                ComplicationType.RANGED_VALUE,
                ComplicationType.GOAL_PROGRESS,
                ComplicationType.SHORT_TEXT,
                ComplicationType.MONOCHROMATIC_IMAGE,
            ),
            defaultDataSourcePolicy =
                DefaultComplicationDataSourcePolicy(src, ComplicationType.SHORT_TEXT),
            bounds = ComplicationSlotBounds(bounds)
        ).build()
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        slots: ComplicationSlotsManager,
        repo: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = LifeRenderer(
            surfaceHolder, watchState, slots, repo, applicationContext, slotDrawables
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer).apply {
            setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?
                ) {
                    if (tapType != TapType.UP) return
                    if (complicationSlot != null) {
                        // Forward to the complication's own tap action, as usual.
                        val action = when (val d = complicationSlot.complicationData.value) {
                            is ShortTextComplicationData   -> d.tapAction
                            is RangedValueComplicationData -> d.tapAction
                            is LongTextComplicationData    -> d.tapAction
                            else                           -> null
                        }
                        try { action?.send() } catch (_: Exception) { }
                    } else {
                        // Bare face: seed life where the finger landed.
                        renderer.queueTap(tapEvent.xPos.toFloat(), tapEvent.yPos.toFloat())
                    }
                }
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private class LifeRenderer(
        surfaceHolder: SurfaceHolder,
        private val state: WatchState,
        private val slots: ComplicationSlotsManager,
        private val styleRepo: CurrentUserStyleRepository,
        private val ctx: Context,
        private val slotDrawables: List<ComplicationDrawable>
    ) : Renderer.CanvasRenderer2<LifeRenderer.Assets>(
        surfaceHolder, styleRepo, state, CanvasType.HARDWARE, 100L, true
    ) {
        private val N = LifeEngine.N
        private val rnd = Random(System.nanoTime())
        private val engine = LifeEngine(rnd)

        // One 64x64 buffer per layer; all three are blitted, never iterated per pixel
        // at screen resolution.
        private val fieldPixels = IntArray(LifeEngine.CELLS)
        private val glyphPixels = IntArray(LifeEngine.CELLS)
        private val glowPixels  = IntArray(LifeEngine.CELLS)
        private val fieldBmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        private val glyphBmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        private val glowBmp  = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        private val srcRect = Rect(0, 0, N, N)
        private val dstRect = RectF()

        private val pCrisp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        private val pSoft  = Paint().apply { isFilterBitmap = true;  isAntiAlias = true }
        private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 5f
        }
        private val pRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val pRead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private var theme: Theme = Theme.PHOSPHOR
        private var themeApplied = false
        private var lastMinute = -1
        private var lastStepMs = 0L
        private var lastSplashMs = 0L
        private var prevGlyphs: LongArray? = null

        /** Set from the UI thread by a tap, consumed on the render thread. */
        private val pendingTap = AtomicReference<Pair<Float, Float>?>(null)

        fun queueTap(x: Float, y: Float) { pendingTap.set(x to y) }

        // ── styling ───────────────────────────────────────────────────────────

        private fun applyTheme(t: Theme) {
            if (t == theme && themeApplied) return
            theme = t
            themeApplied = true
            pArc.color = t.accent
            pRim.color = t.faint
            pRead.color = t.dim
            for (d in slotDrawables) {
                d.activeStyle.textColor = t.accent
                d.activeStyle.titleColor = t.dim
                d.activeStyle.iconColor = t.accent
                d.activeStyle.rangedValuePrimaryColor = t.accent
                d.activeStyle.rangedValueSecondaryColor = t.faint
                d.ambientStyle.textColor = t.dim
                d.ambientStyle.titleColor = t.faint
                d.ambientStyle.iconColor = t.dim
                d.ambientStyle.rangedValuePrimaryColor = t.dim
                d.ambientStyle.rangedValueSecondaryColor = t.faint
            }
            rebuildGlyphLayers()
        }

        // ── the time, as cells ────────────────────────────────────────────────

        /** Bitboard of the numerals for `hh:mm`, centred on the grid. */
        private fun stampTime(t: ZonedDateTime): LongArray {
            val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
            val h = if (is24) t.hour else ((t.hour + 11) % 12) + 1
            val text = "%02d:%02d".format(h, t.minute)
            val w = PixelFont.widthOf(text, DIGIT_SCALE)
            return PixelFont.stamp(
                text, DIGIT_SCALE,
                (N - w) / 2,
                (N - PixelFont.HEIGHT * DIGIT_SCALE) / 2
            )
        }

        /**
         * Rebuilds the dead-cell mask for a new minute, and lets the outgoing
         * numerals go. The burst is the two-cell shell *around* the old glyphs
         * rather than the glyphs themselves: the new numerals occupy nearly the same
         * cells and are masked dead, so cells released from inside them would be
         * erased on the same frame and nothing would be seen.
         */
        private fun onMinuteChanged(t: ZonedDateTime) {
            val glyphs = stampTime(t)

            prevGlyphs?.let { old ->
                val shell = LifeEngine.dilate(old, 3)
                val core = LifeEngine.dilate(old, 1)
                engine.inject(LongArray(N) { shell[it] and core[it].inv() }, thin = true)
            }

            val dead = LifeEngine.dilate(glyphs, 1)
            val mask = LongArray(N) { dead[it].inv() }
            carve(mask, TOP_BOUNDS)
            carve(mask, BL_BOUNDS)
            carve(mask, BR_BOUNDS)
            carve(mask, RectF(0.20f, READOUT_Y - 0.030f, 0.80f, READOUT_Y + 0.030f))
            engine.setMask(mask)

            prevGlyphs = glyphs
            rebuildGlyphLayers()
        }

        /** Clears a screen-fraction rectangle (plus a cell of halo) out of the mask. */
        private fun carve(mask: LongArray, box: RectF) {
            val c0 = ((box.left * N).toInt() - 1).coerceIn(0, N - 1)
            val c1 = ((box.right * N).toInt() + 1).coerceIn(0, N - 1)
            val r0 = ((box.top * N).toInt() - 1).coerceIn(0, N - 1)
            val r1 = ((box.bottom * N).toInt() + 1).coerceIn(0, N - 1)
            val span = if (c1 - c0 + 1 >= 64) -1L else ((1L shl (c1 - c0 + 1)) - 1L) shl c0
            for (r in r0..r1) mask[r] = mask[r] and span.inv()
        }

        /**
         * Bakes the numerals and their halo into two 64x64 buffers.
         *
         * The glow is three concentric dilations at falling alpha. Drawn back up to
         * screen size with bilinear filtering, those steps blend into a smooth
         * gradient — which is how the face gets a CRT bloom without a blur pass,
         * a software layer, or anything that runs per frame.
         */
        private fun rebuildGlyphLayers() {
            val glyphs = prevGlyphs ?: return
            java.util.Arrays.fill(glyphPixels, Color.TRANSPARENT)
            java.util.Arrays.fill(glowPixels, Color.TRANSPARENT)

            // Two rings, not three: a block font dilated by 3 merges the numerals
            // into one solid rectangle, and the bloom reads as a box behind the time
            // rather than a halo around it.
            val rings = listOf(
                LifeEngine.dilate(glyphs, 2) to 0x22,
                LifeEngine.dilate(glyphs, 1) to 0x5A,
            )
            for ((ring, alpha) in rings) {
                val c = Theme.withAlpha(theme.accent, alpha)
                for (r in 0 until N) {
                    var w = ring[r]
                    while (w != 0L) {
                        val col = java.lang.Long.numberOfTrailingZeros(w)
                        glowPixels[r * N + col] = c
                        w = w and (w - 1)
                    }
                }
            }
            for (r in 0 until N) {
                var w = glyphs[r]
                while (w != 0L) {
                    val col = java.lang.Long.numberOfTrailingZeros(w)
                    glyphPixels[r * N + col] = theme.accent
                    glowPixels[r * N + col] = Color.TRANSPARENT   // no glow under the fill
                    w = w and (w - 1)
                }
            }
            glyphBmp.setPixels(glyphPixels, 0, N, 0, 0, N, N)
            glowBmp.setPixels(glowPixels, 0, N, 0, 0, N, N)
        }

        // ── simulation cadence ────────────────────────────────────────────────

        private fun advance(now: Long, genMs: Long) {
            if (genMs == Long.MAX_VALUE) return
            // Cap the catch-up so a face returning from ambient does not burn a
            // hundred generations in one frame.
            var budget = 4
            while (now - lastStepMs >= genMs && budget-- > 0) {
                engine.step()
                lastStepMs += genMs
            }
            if (now - lastStepMs > genMs * 8) lastStepMs = now
        }

        /** Keeps the board from settling into a still picture. */
        private fun revive(now: Long) {
            if (!engine.stagnant && engine.population >= 120) return
            if (now - lastSplashMs < 1200L) return
            lastSplashMs = now
            engine.splash(rnd.nextInt(N), rnd.nextInt(N), radius = 10)
        }

        // ── assets ────────────────────────────────────────────────────────────

        class Assets : Renderer.SharedAssets {
            // Nothing to release: every buffer is owned by the renderer instance.
            // Opening the on-watch editor creates a second renderer sharing this
            // object, so freeing anything here would pull it from under the live one.
            override fun onDestroy() {}
        }

        override suspend fun createSharedAssets() = Assets()

        // ── render ────────────────────────────────────────────────────────────

        override fun render(canvas: Canvas, bounds: Rect, t: ZonedDateTime, a: Assets) {
            val ambient = renderParameters.drawMode == DrawMode.AMBIENT
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val R = bounds.width() * 0.5f

            applyTheme(Theme.byId(optionId(styleRepo, THEME_SETTING)))
            engine.rule = LifeEngine.ruleFor(optionId(styleRepo, RULE_SETTING))
            val motion = motionFor(optionId(styleRepo, MOTION_SETTING))
            val wantFrame = if (ambient) 1000L else motion.frameMs
            if (interactiveDrawModeUpdateDelayMillis != wantFrame) {
                interactiveDrawModeUpdateDelayMillis = wantFrame
            }

            if (t.minute != lastMinute) {
                lastMinute = t.minute
                onMinuteChanged(t)
                if (engine.population == 0) engine.seed()
            }

            pendingTap.getAndSet(null)?.let { (x, y) ->
                engine.rPentomino(
                    ((x - bounds.left) / bounds.width() * N).toInt().coerceIn(0, N - 1),
                    ((y - bounds.top) / bounds.height() * N).toInt().coerceIn(0, N - 1)
                )
            }

            val now = System.currentTimeMillis()
            if (!ambient) {
                advance(now, motion.genMs)
                // Not in Still: a board that is not advancing has nothing to revive,
                // and reviving it would make "Still" quietly fill up with soup.
                if (motion.genMs != Long.MAX_VALUE) revive(now)
            }

            canvas.drawColor(Color.BLACK)
            dstRect.set(bounds)

            // Burn-in protection: nudge the whole composition by a couple of pixels
            // each minute so a static ambient image never sits on the same OLED
            // sub-pixels for long.
            val shifted = ambient && state.hasBurnInProtection
            if (shifted) {
                canvas.save()
                canvas.translate(((t.minute % 5) - 2).toFloat(), ((t.minute / 5 % 5) - 2).toFloat())
            }

            if (ambient) {
                engine.paintSparseInto(fieldPixels, Theme.withAlpha(theme.accent, 0x4A), Color.BLACK)
            } else {
                engine.paintInto(fieldPixels, theme.ramp, theme.bg)
            }
            fieldBmp.setPixels(fieldPixels, 0, N, 0, 0, N, N)
            canvas.drawBitmap(fieldBmp, srcRect, dstRect, pCrisp)

            // Numerals: soft halo first (bilinear), then the crisp cell blocks.
            if (!ambient) {
                pSoft.alpha = 255
                canvas.drawBitmap(glowBmp, srcRect, dstRect, pSoft)
                canvas.drawBitmap(glyphBmp, srcRect, dstRect, pCrisp)
            } else {
                pCrisp.alpha = 0xB4
                canvas.drawBitmap(glyphBmp, srcRect, dstRect, pCrisp)
                pCrisp.alpha = 255
            }

            if (!ambient) {
                pRead.textSize = R * 0.082f
                canvas.drawText(
                    "GEN %06d   POP %04d".format(engine.generation % 1_000_000, engine.population),
                    cx, cy + R * (READOUT_Y - 0.5f) * 2f + pRead.textSize * 0.36f, pRead
                )

                canvas.drawCircle(cx, cy, R - 4f, pRim)
                val sec = t.second + t.nano / 1_000_000_000f
                canvas.drawArc(
                    cx - R + 6f, cy - R + 6f, cx + R - 6f, cy + R - 6f,
                    -90f, sec / 60f * 360f, false, pArc
                )
            }

            for (slot in slots.complicationSlots.values) {
                if (slot.enabled) slot.render(canvas, t, renderParameters)
            }

            if (shifted) canvas.restore()
        }

        override fun renderHighlightLayer(
            canvas: Canvas, bounds: Rect, t: ZonedDateTime, a: Assets
        ) {
            for (slot in slots.complicationSlots.values)
                slot.renderHighlightLayer(canvas, t, renderParameters)
        }
    }
}
