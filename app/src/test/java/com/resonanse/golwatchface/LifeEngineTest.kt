package com.resonanse.golwatchface

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation step is written as bit-plane arithmetic over whole 64-cell rows,
 * which is fast but not readable enough to trust by inspection. These tests pin it
 * against a naive per-cell implementation and against known Life patterns.
 */
class LifeEngineTest {

    private val N = LifeEngine.N

    private fun engine(seed: Long = 1) = LifeEngine(Random(seed))

    private fun board(vararg cells: Pair<Int, Int>): LongArray {
        val rows = LongArray(N)
        for ((c, r) in cells) rows[r] = rows[r] or (1L shl c)
        return rows
    }

    private fun LifeEngine.load(rows: LongArray) {
        inject(rows)
    }

    /** Straightforward reference: count the eight neighbours of every cell. */
    private fun naive(rows: LongArray, rule: LifeEngine.Companion.Rule): LongArray {
        fun at(c: Int, r: Int) =
            (rows[((r % N) + N) % N] ushr (((c % N) + N) % N)) and 1L == 1L
        val out = LongArray(N)
        for (r in 0 until N) for (c in 0 until N) {
            var n = 0
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                if (at(c + dc, r + dr)) n++
            }
            val alive = at(c, r)
            val live = if (alive) rule.survive and (1 shl n) != 0
                       else rule.birth and (1 shl n) != 0
            if (live) out[r] = out[r] or (1L shl c)
        }
        return out
    }

    @Test fun `step matches a naive neighbour count on random boards`() {
        val rnd = Random(20260904)
        for (rule in LifeEngine.RULES) {
            repeat(40) {
                val start = LongArray(N) { rnd.nextLong() and rnd.nextLong() }
                val e = engine()
                e.rule = rule
                e.load(start)
                var expected = start
                repeat(3) {
                    expected = naive(expected, rule)
                    e.step()
                    assertArrayEquals("rule=${rule.id}", expected, e.rows)
                }
            }
        }
    }

    @Test fun `block is a still life`() {
        val e = engine()
        val block = board(10 to 10, 11 to 10, 10 to 11, 11 to 11)
        e.load(block)
        repeat(5) { e.step() }
        assertArrayEquals(block, e.rows)
        assertEquals(4, e.population)
    }

    @Test fun `blinker oscillates with period two`() {
        val e = engine()
        val blinker = board(20 to 20, 21 to 20, 22 to 20)
        e.load(blinker)
        e.step()
        assertTrue("should have rotated", !e.rows.contentEquals(blinker))
        e.step()
        assertArrayEquals(blinker, e.rows)
    }

    @Test fun `glider translates by one cell diagonally every four generations`() {
        val e = engine()
        // Standard glider, travelling down-right.
        e.load(board(1 to 0, 2 to 1, 0 to 2, 1 to 2, 2 to 2))
        repeat(4) { e.step() }
        assertArrayEquals(board(2 to 1, 3 to 2, 1 to 3, 2 to 3, 3 to 3), e.rows)
    }

    @Test fun `the grid wraps as a torus in both axes`() {
        val e = engine()
        // A blinker straddling the right edge must survive by seeing column 0.
        e.load(board(63 to 5, 0 to 5, 1 to 5))
        e.step()
        assertEquals(3, e.population)
        assertArrayEquals(board(0 to 4, 0 to 5, 0 to 6), e.rows)
    }

    @Test fun `masked cells never come alive`() {
        val e = engine()
        val mask = LongArray(N) { if (it in 30..33) 0L else -1L }
        e.setMask(mask)
        e.seed(density = 1)
        repeat(20) {
            e.step()
            for (r in 30..33) assertEquals("row $r must stay dead", 0L, e.rows[r])
        }
    }

    @Test fun `stagnation is detected once the board settles`() {
        val e = engine()
        e.load(board(10 to 10, 11 to 10, 10 to 11, 11 to 11))   // block: static
        var flagged = false
        repeat(10) { e.step(); flagged = flagged || e.stagnant }
        assertTrue("a still life must be reported as stagnant", flagged)
    }

    @Test fun `age resets on birth and climbs while a cell survives`() {
        val e = engine()
        e.load(board(10 to 10, 11 to 10, 10 to 11, 11 to 11))
        repeat(LifeEngine.MAX_AGE + 3) { e.step() }
        assertEquals(LifeEngine.MAX_AGE.toByte(), e.age[10 * N + 10])
    }

    @Test fun `dilate grows a single cell into a square ring`() {
        val one = board(32 to 32)
        val d = LifeEngine.dilate(one, 1)
        assertEquals(9, d.sumOf { java.lang.Long.bitCount(it) })
        assertEquals(25, LifeEngine.dilate(one, 2).sumOf { java.lang.Long.bitCount(it) })
    }

    @Test fun `pixel font stamps every digit inside its advertised width`() {
        for (text in listOf("00:00", "12:34", "23:59")) {
            val w = PixelFont.widthOf(text, 2)
            assertEquals(50, w)
            val rows = PixelFont.stamp(text, 2, (N - w) / 2, 25)
            assertTrue("glyphs must be drawn", rows.any { it != 0L })
            val allowed = ((1L shl w) - 1L) shl ((N - w) / 2)
            for (r in rows.indices) {
                assertTrue("row $r outside the text box", rows[r] and allowed.inv() == 0L)
                if (r !in 25 until 25 + PixelFont.HEIGHT * 2) assertEquals(0L, rows[r])
            }
        }
    }
}
