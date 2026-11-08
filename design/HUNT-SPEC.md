# Seeker Hunt: fourth HOME MODE (approved idea 2026-10-22 01:03)

Status: APPROVED for build (M8). Defaults taken: miss rate 25%, a miss costs nothing, all eight ducks on screen. Fourth value of HOME MODE: WHEEL / FLAP / INVADERS / HUNT. Playable mockup: `design/seeker-hunt-mockup-v4.html` (v1, v2, v3 superseded) (Enter = OK, arrows = dpad). Port sprites, constants, sounds and screens 1:1 from its JavaScript.

## Idea
Birds in brand colors fly back and forth across a dark sky, name under each. A crosshair locks onto one bird automatically; LEFT/RIGHT jump the lock to the nearest bird on that side. Our hound (with his shotgun) stays planted at center and pivots to face whichever duck is locked (a short tick when he turns), with an ear-and-tail twitch every 1.5 s while he waits. OK shoots: ~1 in 4 shots misses (MISSED flashes at the bird, it flees for a second, the dog hops and yips). A hit drops the bird, the dog hops, then the brand takeover and the app launches at 900 ms. HOME or returning from an app always lands on START. START has SETTINGS under it (DOWN).

## Style: 8-bit, NOT dot matrix (owner, 01:17)
This mode breaks the dot-matrix family on purpose. Square pixels (6 px at 1080p), flat NES-era palette, daytime scene: sky #5C94FC with a lighter band up top, two drifting white clouds, a round-canopy tree on the left, two bushes on the right, grass #00A800 with darker tufts, a darker strip at the very bottom. No rainbow strip in this mode (it would fight the bright scene; the backlight gets sky blue and grass green instead). Text is the 5x7 font drawn as square pixels with a 1-px black drop shadow on light text. Settings is a black panel with a white border, `>` cursor.

## Art (all ours; nothing from the NES game, even though the era is the reference)
- Duck 16x12, indexed colors (b = brand body, d = darker brand wing, k = outline, o = orange beak and feet), two wing frames, mirrored by direction.
- Hound 28x18, indexed: t tan coat, w white chest and eye, d darker-tan floppy ear, n nose, k outline, g gray shotgun over the shoulder, tail up. Two frames: idle / ear-and-tail twitch. Mirrored to face the locked duck. Hop up to 30 px on hit or miss. Palette: DOGPAL in the mockup.
- Crosshair 11x11, black ring with the locked bird's brand color inside.
- Sprites use the indexed-character format in the mockup; port the tables and the palette as-is.

## Layout and motion (1920x1080)
- Ground at y=830: grass fringe of 4 px bars, dim dot field below.
- Eight birds, start x = 200 + i*200 (+ jitter), y = 180 + (i%4)*95 (+ jitter), vx 1.2 to 2.4 px/frame random sign, sine bob; bounce at x 120 / 1800; y clamped 130 to 690. Fleeing: 3.5x speed and a lift for 60 frames.
- Sight eases to the locked bird (18% per frame). Dog does not move; x = 960. He flips to face the locked bird whenever it crosses his x.
- Shot: white flash ring at the sight for 6 frames; a new shot is blocked during the flash.
- HUD: SHOTS count top right. Takeover shows the bird sprite above the name and the shot count.

## Sound (synthesized, no samples, all ours)
- lock: square 1200 Hz, 30 ms, 0.06.
- shot: noise 220 ms through 2 kHz low-pass at 0.6 + square 160→50 Hz 180 ms at 0.25.
- miss: triangle 420→300 Hz 120 ms, then 300→200 Hz 180 ms (120 ms later).
- hit: square 523 / 659 / 784 Hz, 70/70/120 ms, staggered 70 ms.
- yip: square 700→900 Hz 60 ms, then 900→650 Hz 80 ms.
- step (now the pivot tick): square 90→70 Hz 30 ms at 0.04, once per turn.
On the TV: same PCM-at-startup + SoundPool approach as Invaders. SOUND setting applies.

## Open questions for the owner (defaults in brackets)
1. Miss rate [25%]. 2. Should a miss cost anything beyond the taunt [no]. 3. All eight birds on screen at once [yes] or fewer with respawns.

## v4 changes (owner, 2026-10-22 01:29): the dog reacts, the gun aims, hold = miss
- **The dog does not shoot and does not walk.** He is off screen during play. The player's shotgun sits at the bottom center (pivot at x=960, y=GROUND+150, sprite GUN 20x6 at cell 6, pivot at its left end) and rotates to point at the sight.
- **Dog pop-up.** On every shot the dog rises out of the grass at center: 20 frames up, 60 frames hold with a small bob, 20 frames down; drawn clipped to the ground line with the grass tufts redrawn in front. Miss: POP_MISS (grinning, teeth) with the yip at frame 20. Hit: POP_HIT with his arm up to the right and the duck dangling from his paw in its brand color, hit jingle at frame 20. After he sinks on a hit, the takeover runs and the app launches at 900 ms. START shows him peeking out of the grass.
- **Odds.** Tap OK: 25% hit, 75% miss. Hold OK for 600 ms: guaranteed miss (shot fires at the 600 ms mark). New shots are blocked while the dog is up or the flash is on.
- Sprites: POP_MISS and POP_HIT (24x18, cell 8), GUN (20x6, cell 6), DOGPAL and GUNPAL in the mockup. The walking hound from v3 is gone.
