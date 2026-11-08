package com.seeker.tv

/** The game HOME MODEs in the 'flap' flavor. WHEEL is always built in. */
object BuiltModes {
    val GAMES: List<GameMode> = listOf(GameMode("FLAP") { c, h -> FlapView(c, h) })
}
