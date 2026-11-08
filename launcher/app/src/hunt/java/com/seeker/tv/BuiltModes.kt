package com.seeker.tv

/** The game HOME MODEs in the 'hunt' flavor. WHEEL is always built in. */
object BuiltModes {
    val GAMES: List<GameMode> = listOf(GameMode("HUNT") { c, h -> HuntView(c, h) })
}
