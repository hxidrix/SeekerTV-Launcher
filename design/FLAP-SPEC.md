# Seeker Flap: playful alternate home screen (parked idea, 2026-10-21)

Status: ACTIVE (M5), after M3/M4 and the HOME swap. Built as a second launcher mode inside the Seeker app, switchable in Seeker settings (HOME MODE: WHEEL / FLAP). Playable mockup: `design/seeker-flap-mockup-v2.html` (open in a browser, Enter = OK).

## Idea
A Flappy-style game as the home screen, for nights when nobody knows what to watch. Each tube is a streaming service in its brand color with the name stacked down the tube. Crash into a tube and that service opens. Back on the home screen, it's the START screen again.

## Screens
- START: "SEEKER FLAP" title, blinking START (old-school), SETTINGS below it (DOWN selects it), BEST score if any. Bird idles with a bob.
- PLAY: OK flaps. Score counter top center (one point per tube passed). Tuned for TV remote latency (2026-10-21): gravity 0.40 px/frame^2, flap -10.2, scroll 4.8 px/frame at 60 fps (1080p), gap 370 px, a tube every 125 frames (~2.1 s), tube width 150 px with 44 px caps. Values are in the mockup JS; port them.
- CRASH: brand-color circle expands from the bird, app name in dot matrix with OPENING and the score, then the app launches (fire the intent ~900 ms in, same as the Seeker launcher takeover). Floor or ceiling crash = the next tube ahead.
- SETTINGS (DOWN + OK from START): APP LIST (the normal Seeker wheel), GREETING, HIDDEN APPS, SYSTEM SETTINGS, BACK.

## Services
Netflix #E50914, YouTube #FF0000, Hulu #1CE783, Prime #00A8E1, Disney+ #2E6BFF, Max #3D34FF, Peacock #FFC72C, Apple TV #F5F5F7. Only these eight. Light tubes use dark ink for the name, dark tubes use white.

## Art
Our own drawings only: the 11x8 two-frame dot bird and the tubes in the mockup. Nothing from the original game.

## Build notes (when picked up)
- Build it as a mode inside the Seeker launcher app (same package, a `FlapActivity` or a mode switch in settings: WHEEL / FLAP), so there's one HOME app and one settings screen. Toggle lives in Seeker settings as HOME MODE.
- 60 fps on the Canvas view; same dot font and brand table as the wheel. Keep it under the launcher's RAM budget.
- Leaving an app and pressing Home returns to START, never mid-game.

## Rainbow strip (added 2026-10-21 19:57)
The reference TV has bias lighting behind it, so the top edge of the screen drives it. Three rows of 16 px dots across the full width at y=0, hue = (x/W)*360 + t*speed, plus a soft per-column glow fading out over 220 px below the strip. Play and crash: speed 1.6 deg/frame, 100% saturation, 55% lightness, glow alpha 0.28. START and settings: speed 0.5, 70% sat, 42% light, glow 0.12. Score moves down to y=110 to clear it. Exact code in the mockup's `rainbow()`.

## v2 changes (owner, 2026-10-21 22:25)
- **Shuffle bag.** Tubes draw from a bag of all eight services, shuffled; the bag refills and reshuffles only when empty, and the first of a new bag is never the last of the previous one. The bag persists across games so successive crashes vary. Code: `nextApp()` in the mockup.
- **Tube labels never leave the screen.** The name runs along the tube like a book spine (rotated 90°, reads bottom to top), on whichever half is taller, starting 22 px from the cap. Dot cell starts at 6 and shrinks by 0.5 until the name fits in that half minus 44 px; below 3.5 the label is skipped. Code: `drawPipe()` in the mockup.
- **On the TV, add the app's own icon.** Draw the app's launcher icon (PackageManager, 72 px, rounded) on the cap at the mouth of the gap, both halves. The rotated name stays on the body when it fits. The icon is the primary label; the name is secondary.
