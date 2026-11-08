# Seeker Invaders: third HOME MODE (approved idea 2026-10-21 23:46)

Status: QUEUED as M6, after Flap v2. Third value of HOME MODE: WHEEL / FLAP / INVADERS. Playable mockup: `design/seeker-invaders-mockup-v1.html` (Enter = OK, arrows = dpad). Port the sprites, layout constants, sound recipes and screens 1:1 from its JavaScript.

## Idea
Your ship at the bottom, tile-stepped left/right, OK fires. The eight streaming services are critters in a slow-drifting row at the top, each in its brand color with the name under it. Shoot one and it opens (brand takeover, launch at 900 ms, same as the wheel and Flap). A gray meteor sits top-right, outside the critters' drift range, labeled SETTINGS: shoot it to open Seeker settings. HOME or returning from an app always lands on START.

## Layout (1920x1080 reference)
- Ship: 13x7 sprite at cell 6, y=900, rides a dotted rail. 26 tiles of 64 px starting at x=160. Moves exactly one tile per LEFT/RIGHT press. Starts at tile 12.
- Critters: 8 across, spacing 170 px, first center x=200, y=250, cell 7. Three species (11x8, 8x8, 12x8), two animation frames each, species = index mod 3. Name under each at cell 3.5 in #70757E.
- Formation drifts as a block: 22 px every 48 frames, reversing at ±110 px. Frame flips on each step. It never reaches the meteor.
- Meteor: 12x10 rock at cell 7, center (1700, 150), gentle bob, #8F969F, label SETTINGS.
- Bullet: 6x26, 16 px/frame, one on screen at a time. Hit test against critter and meteor boxes (6 px slack).
- Hit: 8-spoke burst in the brand color over 14 frames, critter vanishes, then the takeover (circle from the critter, its sprite above the name, OPENING, SHOTS count). After ~2.2 s back to START.
- Rainbow strip on top, calm on START, full in play (same recipe as Flap).
- START: title SEEKER INVADERS, blinking START, "SHOOT A CRITTER TO OPEN IT", the critters dimmed in a row, the ship.

## Sound (synthesized, no samples)
- move: square 180 Hz, 45 ms, gain 0.08. One per tile step.
- fire: sawtooth 900→180 Hz sweep, 140 ms, gain 0.12.
- hit: white-noise burst 350 ms through a 900 Hz low-pass, gain 0.5, plus square 120→40 Hz thump 300 ms.
- march: square, alternating 98 / 87 Hz, 80 ms, gain 0.07, on every formation step while playing.
- meteor: triangle 300→700 Hz, 250 ms.
On the TV: AudioTrack or SoundPool with short generated PCM buffers made at startup (no asset files). Mute toggle in Seeker settings (SOUND: ON / OFF) that applies to all modes.

## Art
Our own sprites only (the three critters, ship and rock in the mockup). Nothing from the original arcade game.

## Build notes
- Same `FlapView`-style Canvas view as Flap; share the dot font, brand table, takeover and settings screen.
- 60 fps; the formation only moves every 48 frames so draw cost is low. Watch GPU like Flap.
- Settings gains HOME MODE: WHEEL / FLAP / INVADERS and SOUND: ON / OFF.
