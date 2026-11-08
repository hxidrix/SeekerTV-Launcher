package com.seeker.tv

/** The game HOME MODEs in the 'all' flavor. WHEEL is always built in. */
object BuiltModes {
    val GAMES: List<GameMode> = listOf(
        GameMode("FLAP") { c, h -> FlapView(c, h) },
        GameMode("INVADERS") { c, h -> InvadersView(c, h) },
        GameMode("HUNT") { c, h -> HuntView(c, h) },
    )
}
