package com.seeker.tv

/** The game HOME MODEs in the 'invaders' flavor. WHEEL is always built in. */
object BuiltModes {
    val GAMES: List<GameMode> = listOf(GameMode("INVADERS") { c, h -> InvadersView(c, h) })
}
