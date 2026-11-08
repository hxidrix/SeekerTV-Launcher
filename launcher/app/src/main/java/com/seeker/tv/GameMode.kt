package com.seeker.tv

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.KeyEvent

/** What a game HOME MODE needs from MainActivity. */
interface GameHost {
    fun launch(entry: AppEntry)
    fun openSettings()                 // SETTINGS on the START screen
    fun flapEntry(pkg: String): AppEntry?
    fun flapIcon(entry: AppEntry): Drawable?
    fun flapBest(): Int
    fun saveFlapBest(best: Int)
    fun firstFrame()
}

/** A game HOME MODE's screen. Every implementation is a View. */
interface GameScreen {
    fun toStart()
    fun refreshApps()
    fun onKey(e: KeyEvent): Boolean
}

/**
 * One game HOME MODE. Which modes exist is decided by the build flavor: each flavor's
 * `BuiltModes.GAMES` lists only the games compiled into it (src/modeFlap, src/modeInvaders, src/modeHunt).
 */
class GameMode(val name: String, val create: (Context, GameHost) -> GameScreen)
