package com.resonanse.golwatchface

/**
 * A 5x7 pixel font, stamped onto the same cell grid the simulation runs on.
 *
 * Drawing the time as cells rather than as text is what ties the two halves of the
 * face together: the digits sit exactly on the Life lattice, so when a minute rolls
 * over and the old numerals are turned loose into the board there is no seam
 * between "text" and "simulation" — it is all the same grid.
 */
object PixelFont {

    private val GLYPHS = mapOf(
        '0' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to arrayOf(".", "#", ".", ".", ".", "#", "."),
    )

    const val HEIGHT = 7
    private const val TRACKING = 1          // blank columns between glyphs, unscaled

    fun widthOf(text: String, scale: Int): Int {
        var w = 0
        for (ch in text) w += (GLYPHS[ch]!![0].length + TRACKING) * scale
        return w - TRACKING * scale
    }

    /**
     * Renders `text` into a fresh bitboard, top-left at cell (x, y).
     * Cells that fall outside the grid are dropped rather than wrapped, so a string
     * that is too wide degrades by clipping instead of smearing across the torus.
     */
    fun stamp(text: String, scale: Int, x: Int, y: Int): LongArray {
        val out = LongArray(LifeEngine.N)
        var penX = x
        for (ch in text) {
            val g = GLYPHS[ch] ?: continue
            for (gr in g.indices) {
                val line = g[gr]
                for (gc in line.indices) {
                    if (line[gc] != '#') continue
                    for (dy in 0 until scale) {
                        val r = y + gr * scale + dy
                        if (r !in 0 until LifeEngine.N) continue
                        var bits = 0L
                        for (dx in 0 until scale) {
                            val c = penX + gc * scale + dx
                            if (c in 0 until LifeEngine.N) bits = bits or (1L shl c)
                        }
                        out[r] = out[r] or bits
                    }
                }
            }
            penX += (g[0].length + TRACKING) * scale
        }
        return out
    }
}
