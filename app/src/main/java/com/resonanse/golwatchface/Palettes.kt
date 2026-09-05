package com.resonanse.golwatchface

import android.graphics.Color

/**
 * A theme is one accent plus a five-stop age ramp.
 *
 * The ramp is what gives the board depth: a cell is drawn at its brightest the
 * generation it is born and fades as it survives, so gliders and newly disturbed
 * regions read as bright motion while stable debris sinks into the background.
 * Without it the field is a flat noise texture and the eye has nothing to follow.
 */
data class Theme(
    val id: String,
    val label: String,
    val accent: Int,
    private val young: Int,
    private val old: Int,
    val bg: Int
) {
    /** Index 0 = just born, [LifeEngine.MAX_AGE] = long-settled. */
    val ramp: IntArray = IntArray(LifeEngine.MAX_AGE + 1) { i ->
        val t = i.toFloat() / LifeEngine.MAX_AGE
        Color.rgb(
            lerp(Color.red(young),   Color.red(old),   t),
            lerp(Color.green(young), Color.green(old), t),
            lerp(Color.blue(young),  Color.blue(old),  t)
        )
    }

    val dim: Int  = withAlpha(accent, 0x8C)
    val faint: Int = withAlpha(accent, 0x40)

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()

    companion object {
        fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        val PHOSPHOR = Theme("phosphor", "Phosphor",
            accent = Color.rgb(90, 255, 130),
            young  = Color.rgb(200, 255, 215),
            old    = Color.rgb(14, 62, 30),
            bg     = Color.rgb(3, 6, 4))

        val AMBER = Theme("amber", "Amber",
            accent = Color.rgb(255, 156, 46),
            young  = Color.rgb(255, 225, 180),
            old    = Color.rgb(60, 30, 4),
            bg     = Color.rgb(6, 4, 2))

        val ICE = Theme("ice", "Ice",
            accent = Color.rgb(95, 216, 255),
            young  = Color.rgb(215, 245, 255),
            old    = Color.rgb(10, 44, 64),
            bg     = Color.rgb(2, 5, 8))

        val BONE = Theme("bone", "Bone",
            accent = Color.rgb(236, 236, 240),
            young  = Color.rgb(255, 255, 255),
            old    = Color.rgb(44, 44, 50),
            bg     = Color.rgb(4, 4, 5))

        val ALL = listOf(PHOSPHOR, AMBER, ICE, BONE)

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: PHOSPHOR
    }
}
