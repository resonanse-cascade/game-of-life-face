package com.resonanse.golwatchface

import kotlin.random.Random

/**
 * Conway's Life on a 64x64 torus, one `Long` per row.
 *
 * The whole point of picking 64 columns is that a row is exactly one machine word:
 * a column shift is a rotate (which *is* the torus wrap, for free), and a generation
 * is computed 64 cells at a time with pure bitwise arithmetic — no per-cell loop, no
 * allocation, ~1300 ops for the entire board. That matters here because the renderer
 * gets a few milliseconds per frame on a watch CPU that is also driving the display.
 *
 * Neighbour counting is a 4-bit-plane adder network. For each row we build the
 * horizontal 3-sums of the row above and below (each 0..3, two bit-planes) and the
 * 2-sum of the row itself excluding the centre cell (0..2), then add those three
 * 2-bit numbers with two ripple stages into planes s0..s3 holding the neighbour
 * count 0..8. A rule test for "count == k" is then four ANDs against those planes.
 *
 * Because the count is exact rather than the classic count-plus-self trick, any
 * B/S rule works, which is what makes the rule selector in Customize possible.
 */
class LifeEngine(private val rnd: Random = Random(System.nanoTime())) {

    companion object {
        const val N = 64                       // grid is N x N, N must stay 64
        const val CELLS = N * N
        const val MAX_AGE = 5                  // colour ramp depth
        private const val HISTORY = 32         // board hashes kept for cycle detection

        /** Birth/survive sets, as bit k = "count k applies". */
        data class Rule(val id: String, val label: String, val birth: Int, val survive: Int)

        val CONWAY   = Rule("conway",   "Conway",   b(3),       b(2, 3))
        val HIGHLIFE = Rule("highlife", "HighLife", b(3, 6),    b(2, 3))
        val MAZE     = Rule("maze",     "Maze",     b(3),       b(1, 2, 3, 4, 5))
        val SEEDS    = Rule("seeds",    "Seeds",    b(2),       0)
        val RULES = listOf(CONWAY, HIGHLIFE, MAZE, SEEDS)

        fun ruleFor(id: String?) = RULES.firstOrNull { it.id == id } ?: CONWAY

        private fun b(vararg counts: Int) = counts.fold(0) { acc, k -> acc or (1 shl k) }

        private fun rotl(x: Long) = java.lang.Long.rotateLeft(x, 1)
        private fun rotr(x: Long) = java.lang.Long.rotateRight(x, 1)

        /** One cell of Moore-neighbourhood growth, applied `times` over. */
        fun dilate(src: LongArray, times: Int): LongArray {
            var cur = src
            repeat(times) {
                val wide = LongArray(N) { cur[it] or rotl(cur[it]) or rotr(cur[it]) }
                cur = LongArray(N) { wide[it] or wide[(it + N - 1) % N] or wide[(it + 1) % N] }
            }
            return cur
        }
    }

    var rows = LongArray(N); private set
    private var next = LongArray(N)

    /** Cells allowed to be alive. The time glyphs and the text rows are held dead. */
    private var mask = LongArray(N) { -1L }

    /** Frames-since-birth per cell, clamped to MAX_AGE, driving the colour ramp. */
    val age = ByteArray(CELLS)

    var rule: Rule = CONWAY
    var generation = 0L; private set
    var population = 0; private set

    private val history = LongArray(HISTORY)
    private var historyAt = 0

    /** True when the board has repeated a state it held in the last [HISTORY] steps. */
    var stagnant = false; private set

    // ── seeding ───────────────────────────────────────────────────────────────

    /** Random soup over the whole board. `density` is an AND-depth: 1 ≈ 50%, 2 ≈ 25%. */
    fun seed(density: Int = 2) {
        for (r in 0 until N) {
            var w = rnd.nextLong()
            repeat(density - 1) { w = w and rnd.nextLong() }
            rows[r] = w and mask[r]
        }
        java.util.Arrays.fill(age, 0)
        resetHistory()
        recount()
    }

    /** A disc of soup, used to revive a board that has gone static. */
    fun splash(cc: Int, cr: Int, radius: Int, density: Int = 1) {
        for (dr in -radius..radius) {
            val r = ((cr + dr) % N + N) % N
            val span = Math.sqrt((radius * radius - dr * dr).toDouble()).toInt()
            for (dc in -span..span) {
                val c = ((cc + dc) % N + N) % N
                var alive = rnd.nextBoolean()
                repeat(density - 1) { alive = alive && rnd.nextBoolean() }
                if (alive) setCell(c, r)
            }
        }
        resetHistory()
        recount()
    }

    /**
     * The R-pentomino: five cells that stay chaotic for 1103 generations before
     * settling. Dropped where the wearer taps, so a tap visibly restarts the board.
     */
    fun rPentomino(cc: Int, cr: Int) {
        val shape = arrayOf(0 to 1, 0 to 2, 1 to 0, 1 to 1, 2 to 1)
        for ((dc, dr) in shape) setCell((cc + dc - 1 + N) % N, (cr + dr - 1 + N) % N)
        resetHistory()
        recount()
    }

    /** Turns a pattern loose on the board — used to scatter the outgoing minute. */
    fun inject(pattern: LongArray, thin: Boolean = false) {
        for (r in 0 until N) {
            var w = pattern[r]
            if (thin) w = w and rnd.nextLong()
            rows[r] = (rows[r] or w) and mask[r]
        }
        resetHistory()
        recount()
    }

    private fun setCell(c: Int, r: Int) {
        if (mask[r] and (1L shl c) == 0L) return
        rows[r] = rows[r] or (1L shl c)
        age[r * N + c] = 0
    }

    fun setMask(m: LongArray) {
        mask = m
        for (r in 0 until N) rows[r] = rows[r] and m[r]
        resetHistory()
        recount()
    }

    // ── the generation step ───────────────────────────────────────────────────

    fun step() {
        val src = rows
        val dst = next
        val birth = rule.birth
        val survive = rule.survive

        for (r in 0 until N) {
            val u = src[if (r == 0) N - 1 else r - 1]
            val m = src[r]
            val d = src[if (r == N - 1) 0 else r + 1]

            // horizontal 3-sums of the neighbouring rows -> (hi, lo) bit-planes, 0..3
            var t = rotl(u) xor u
            val u0 = t xor rotr(u)
            val u1 = (rotl(u) and u) or (t and rotr(u))
            t = rotl(d) xor d
            val d0 = t xor rotr(d)
            val d1 = (rotl(d) and d) or (t and rotr(d))
            // own row contributes only its two horizontal neighbours, 0..2
            val ml = rotl(m); val mr = rotr(m)
            val m0 = ml xor mr
            val m1 = ml and mr

            // add the three 2-bit numbers -> neighbour count in planes s0..s3
            val x = u0 xor d0
            val s0 = x xor m0
            val k0 = (u0 and d0) or (x and m0)
            val y = u1 xor d1
            val t0 = y xor m1
            val tc = (u1 and d1) or (y and m1)
            val s1 = t0 xor k0
            val k1 = t0 and k0
            val s2 = tc xor k1
            val s3 = tc and k1

            var born = 0L
            var surv = 0L
            for (k in 0..8) {
                val kb = 1 shl k
                val wantB = birth and kb != 0
                val wantS = survive and kb != 0
                if (!wantB && !wantS) continue
                var e = if (k and 1 != 0) s0 else s0.inv()
                e = e and (if (k and 2 != 0) s1 else s1.inv())
                e = e and (if (k and 4 != 0) s2 else s2.inv())
                e = e and (if (k and 8 != 0) s3 else s3.inv())
                if (wantB) born = born or e
                if (wantS) surv = surv or e
            }
            dst[r] = ((born and m.inv()) or (surv and m)) and mask[r]
        }

        // Age: a cell that was already alive ages, a newborn resets to 0. Only live
        // cells are visited (Kernighan's bit walk), so an empty board costs nothing.
        for (r in 0 until N) {
            val alive = dst[r]
            val kept = alive and src[r]
            var w = alive
            val base = r * N
            while (w != 0L) {
                val c = java.lang.Long.numberOfTrailingZeros(w)
                val i = base + c
                age[i] = if (kept and (1L shl c) != 0L)
                    minOf(age[i] + 1, MAX_AGE).toByte() else 0
                w = w and (w - 1)
            }
        }

        rows = dst
        next = src
        generation++
        recount()
        noteHistory()
    }

    private fun recount() {
        var p = 0
        for (r in 0 until N) p += java.lang.Long.bitCount(rows[r])
        population = p
    }

    private fun resetHistory() {
        java.util.Arrays.fill(history, 0L)
        historyAt = 0
        stagnant = false
    }

    /**
     * Still lifes and short oscillators are the normal end state of a soup, and a
     * frozen board is a dead watch face. Hashing each generation and looking for a
     * repeat inside a 32-step window catches every oscillator up to period 32,
     * which in practice is all of them.
     */
    private fun noteHistory() {
        var h = -0x61c8864680b583ebL
        for (r in 0 until N) {
            h = h xor rows[r]
            h *= -0x61c8864680b583ebL
            h = java.lang.Long.rotateLeft(h, 27)
        }
        stagnant = history.any { it == h }
        history[historyAt] = h
        historyAt = (historyAt + 1) % HISTORY
    }

    // ── output ────────────────────────────────────────────────────────────────

    /** Paints the board into an ARGB row-major buffer sized [CELLS]. */
    fun paintInto(pixels: IntArray, ramp: IntArray, bg: Int) {
        java.util.Arrays.fill(pixels, bg)
        for (r in 0 until N) {
            var w = rows[r]
            val base = r * N
            while (w != 0L) {
                val c = java.lang.Long.numberOfTrailingZeros(w)
                pixels[base + c] = ramp[age[base + c].toInt()]
                w = w and (w - 1)
            }
        }
    }

    /** Ambient variant: every third cell only, to keep lit pixels down on OLED. */
    fun paintSparseInto(pixels: IntArray, colour: Int, bg: Int) {
        java.util.Arrays.fill(pixels, bg)
        for (r in 0 until N) {
            var w = rows[r]
            val base = r * N
            while (w != 0L) {
                val c = java.lang.Long.numberOfTrailingZeros(w)
                if ((r + c) % 3 == 0) pixels[base + c] = colour
                w = w and (w - 1)
            }
        }
    }
}
