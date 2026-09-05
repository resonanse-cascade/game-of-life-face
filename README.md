# GAME OF LIFE — a Wear OS watch face

Conway's Game of Life running as the background of a watch face, with the time
stamped onto the same cell lattice the simulation lives on. Built for a Galaxy
Watch 5 (Wear OS 4/5), structured after
[resonanse-cascade/hl-watchface](https://github.com/resonanse-cascade/hl-watchface).

![preview](app/src/main/res/drawable-nodpi/preview_watch_face.png)

## What it does

* A 64×64 toroidal Life board fills the face and evolves continuously.
* The time is drawn in a 5×7 pixel font **on the cell grid**, so the numerals are
  made of the same cells as the simulation — not text floating over a background.
* Those numeral cells, plus a one-cell halo and the boxes the small type and
  complications occupy, are held permanently dead. That mask is what keeps the
  face readable; without it the digits vanish into the churn within seconds.
* **When the minute rolls over, the old time disperses.** The two-cell shell
  around the outgoing numerals is released into the board as live cells and
  evolves away. (The shell rather than the glyphs themselves: the new numerals
  occupy nearly the same cells and are masked dead, so anything released from
  inside them would be erased on the same frame.)
* **Tap the face** — anywhere off a complication — to drop an R-pentomino there:
  five cells that stay chaotic for 1103 generations.
* A soup always decays into still lifes and short oscillators, which on a watch
  face means a frozen picture. Every generation is hashed, and when the board
  repeats itself inside a 32-step window or nearly dies out, a disc of fresh soup
  drops in.
* Newly born cells are drawn brightest and fade as they survive, so gliders and
  disturbed regions read as motion instead of flat noise.
* Three complication slots (top, lower-left, lower-right), a seconds arc on the
  rim, and a `GEN nnnnnn POP nnnn` readout under the time.

## Customize (long-press the face → Customize)

| Setting | Options |
| --- | --- |
| Theme  | Phosphor · Amber · Ice · Bone |
| Rule   | Conway `B3/S23` · HighLife `B36/S23` · Maze `B3/S12345` · Seeds `B2/S` |
| Motion | Fast (15 fps) · Normal (5 fps) · Calm (2 fps) · Still |
| Slots  | Top, Lower L, Lower R — any complication data source |

Each preset draws exactly one frame per generation. The only thing that differs
between two frames of the same generation is the seconds arc, so drawing several
frames per step repaints an identical board for nothing — and since this face has
no background cost at all (no sensors, no services, no wake locks), the frames
drawn while you are looking at it are its entire power budget.

`Still` advances the board only when the minute changes, and redraws once a
second for the seconds arc.

## How the simulation is written

The grid is 64 columns wide because that makes a row exactly one `Long`: a column
shift is a rotate, which *is* the torus wrap, for free.

A generation is computed with bit-plane arithmetic, 64 cells at a time. For each
row the horizontal 3-sums of the rows above and below (0..3, two bit-planes) and
the 2-sum of the row itself excluding its centre (0..2) are added by a two-stage
ripple adder into four planes holding the exact neighbour count 0..8. Testing
"count == k" is then four ANDs. The whole board — 4096 cells — costs roughly 1300
integer operations and no allocation.

Counting exactly, rather than using the classic count-plus-self trick that only
answers "is it 3 or 4", is what makes arbitrary `B/S` rules possible, and hence
the rule selector.

Rendering follows the same idea: the board is written into a 64×64 pixel buffer
and blitted once with filtering off, so the background is one texture upload per
frame instead of 4096 draw calls. The numerals' CRT bloom is two concentric
dilations at falling alpha baked into another 64×64 buffer once a minute — drawn
back up to screen size with bilinear filtering, those steps blend into a smooth
gradient, with no blur pass and nothing per-frame. (Two rings, not three: this is
a block font, and dilating it by three merges the numerals into one rectangle, so
the bloom reads as a box behind the time rather than a halo around it.)

The core is plain Kotlin with no Android dependency, so it is covered by ordinary
JVM tests — including a comparison against a naive per-cell neighbour count over
random boards, for every rule:

```
./gradlew testDebugUnitTest
```

## Build and install

Needs the Android SDK and a JDK (Android Studio ships one; `build.sh` finds it).

```bash
./build.sh              # build only
./build.sh --test       # build and run the simulation tests
./build.sh --install    # build, then install to the watch over adb
```

Installing is opt-in because adb talks to one watch at a time. The application id
is `com.resonanse.golwatchface`, distinct from other side-loaded faces, so this
installs *alongside* them rather than replacing one.

To pair the watch: on the watch, Settings → About → Software info → tap Software
version 7×, then Settings → Developer options → ADB debugging + Wireless
debugging → Pair new device, and on the computer `adb pair <ip>:<port>` followed
by `adb connect <ip>:<port>`.

After installing: long-press the current face, swipe to **GAME OF LIFE**, tap it.

## Layout

Everything is positioned in screen fractions, so it scales to any round Wear
display:

```
  y 0.085–0.185   top complication          (date by default)
  y 0.39 –0.61    HH:MM, 5x7 font at 2x     (rows 25–38 of the 64-cell grid)
  y 0.66 –0.72    GEN / POP readout
  y 0.745–0.845   lower-left + lower-right  (battery, steps by default)
```

## Ambient mode

The board freezes, only every third live cell is drawn and at low alpha, the
numerals lose their bloom, and the arc and readout are dropped — then the whole
composition is nudged a couple of pixels per minute where the display reports
burn-in protection, so a static image never sits on the same OLED sub-pixels.

**On a Galaxy Watch 5 with always-on display off, none of this is ever shown.**
Verified on device: on battery the screen simply powers down, and on the charger
Samsung's own charging UI takes over rather than handing the dozing screen to the
watch face. The path is kept because it is correct the moment AOD is enabled, but
it is unreachable otherwise — which is why the frame rate, not ambient, is where
this face's power budget actually lives.

## Notes

* `tools/preview.py` renders the layout to a PNG on the desktop (Pillow). It is
  how the composition was designed without flashing the watch, and it generates
  `preview_watch_face.png`. Run: `python tools/preview.py phosphor`.
* Heart rate is deliberately not built in. Samsung Health will not feed a real
  BPM to a side-loaded face's complication, and reading it directly needs Health
  Services plus `BODY_SENSORS` — out of scope here.
