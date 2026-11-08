package com.seeker.tv

/**
 * The eight streaming services the game modes play with (Flap's tubes, Invaders' critters), in the
 * mockups' order, with the mockups' names and brand colors. [ink] = name color on the brand color
 * (dark on light brands).
 */
class GameApp(val pkg: String, val n: String, val c: Int, val ink: Int)

object GameApps {
    const val INK_DARK = 0xFF0B0C0E.toInt()
    const val INK_WHITE = 0xFFFFFFFF.toInt()

    val ALL = listOf(
        GameApp("com.netflix.ninja", "NETFLIX", 0xFFE50914.toInt(), INK_WHITE),
        GameApp("com.google.android.youtube.tv", "YOUTUBE", 0xFFFF0000.toInt(), INK_WHITE),
        GameApp("com.hulu.livingroomplus", "HULU", 0xFF1CE783.toInt(), INK_DARK),
        GameApp("com.amazon.amazonvideo.livingroom", "PRIME", 0xFF00A8E1.toInt(), INK_DARK),
        GameApp("com.disney.disneyplus", "DISNEY+", 0xFF2E6BFF.toInt(), INK_WHITE),
        GameApp("com.wbd.stream", "MAX", 0xFF3D34FF.toInt(), INK_WHITE),
        GameApp("com.peacocktv.peacockandroid", "PEACOCK", 0xFFFFC72C.toInt(), INK_DARK),
        GameApp("com.apple.atve.androidtv.appletv", "APPLE TV", 0xFFF5F5F7.toInt(), INK_DARK),
    )

    /** The services that are installed, in table order (all eight if none resolve). */
    fun installed(resolve: (String) -> AppEntry?): List<GameApp> = ALL.filter { resolve(it.pkg) != null }.ifEmpty { ALL }
}
