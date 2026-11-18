# VOIDBEAT

A 4-key vertical rhythm game (osu!mania-style) written in plain **Java Swing**. It has no
dependencies and ships with 5 original tracks that are synthesized in code when the game starts,
so the built-in music is 100% copyright-free.

**Controls: `D` `F` `J` `K`**

## Windows: just double-click

Download `VOIDBEAT-windows.zip`, extract it, and double-click **`VOIDBEAT.exe`**. It comes with
its own Java runtime, so there's nothing to install. Keep the folder together, because the exe needs
the `app` and `runtime` folders next to it. If SmartScreen warns about an unrecognized app, click
*More info → Run anyway* (the exe isn't code-signed).

To rebuild the zip, run `./packaging/build-windows.sh` on Linux. It needs mingw-w64, a JDK, python3
and curl, and it writes `dist/VOIDBEAT-windows.zip`.

## Run from source

You need a JDK 17 or newer.

```bash
./run.sh            # macOS / Linux
run.bat             # Windows
./build-jar.sh      # optional: builds voidbeat.jar -> java -jar voidbeat.jar
```

On first launch the game composes and mixes its soundtrack, which takes about 2 seconds per song.
You can play the first track as soon as it's ready.

## Features

- **Four difficulties per song.** Charts are generated from the music itself: kicks, snares, the
  lead melody's pitch contour (rising notes move right, falling notes move left), arps, and long
  notes for sustained chords and melodies.
- **osu!mania timing.** Judgements are MARVELOUS / PERFECT / GREAT / GOOD / BAD / MISS. Accuracy
  and score use the osu!mania formulas, and the grades are SS / S / A / B / C / D.
- **Satisfying feedback.** You get hit bursts and sparks, key beams, receptor flashes, combo pops,
  EARLY/LATE readouts, a live hit-error bar, a beat-synced background, a live audio spectrum and
  FULL COMBO / ALL MARVELOUS banners.
- **Low-latency audio.** A custom mixer feeds one audio line, and the song clock compensates for
  the output buffer. Hitsounds play on every key press.
- **Personal bests** for each song and difficulty, plus a results screen with a timing histogram,
  unstable rate (UR) and an offset suggestion.

### Timing windows (± ms)

| Difficulty | Marvelous | Perfect | Great | Good | Bad | Miss | Feel |
|---|---|---|---|---|---|---|---|
| EASY   | 28 | 85 | 125 | 160 | 190 | 225 | very open; early ghost taps are never punished |
| NORMAL | 18 | 52 |  86 | 116 | 141 | 178 | relaxed |
| HARD   | 16 | 40 |  73 | 103 | 127 | 164 | exactly osu!mania OD8 |
| EXPERT | 16 | 37 |  70 | 100 | 124 | 161 | osu!mania OD9 |

Hold-note releases get 1.5× windows. If you hold past the end of a long note, you get MARVELOUS.

## Keys

| Where | Key | Action |
|---|---|---|
| Everywhere | `F11` / `F12` | fullscreen / FPS + audio-latency overlay |
| Song select | `↑↓` `←→` `Enter` | song / difficulty / play |
| Song select | `F3` `F4` | scroll speed − / + |
| Song select | `Tab` | autoplay (watch a chart; the score isn't saved) |
| Song select | `F5` | rescan the `songs/` folder |
| In game | `Esc` | pause (resume / retry / quit) |
| In game | `` ` `` | quick retry |
| In game | `-` `=` | audio offset −5 / +5 ms (applies on retry) |
| Results | `Enter` / `R` | continue / retry |

**Calibrating:** if the results screen says you are hitting consistently late (a positive mean),
raise **Audio offset** in Settings. The game suggests a value for you.

## Adding your own songs

Put `.wav`, `.aiff` or `.au` files in [`songs/`](songs/) and press `F5` in song select. Name the
files `Artist - Title.wav`. The game detects the tempo, beat phase, onsets and loud sections, then
generates all four difficulties. Convert MP3/OGG files first:

```bash
ffmpeg -i "Artist - Title.mp3" "Artist - Title.wav"
```

Only add music you have the right to use. A "no copyright" label on an upload doesn't always mean
the track is free to use, so check the artist's own licence terms. Files in `songs/` are
git-ignored, so they stay on your machine.

## Developer checks

```bash
./run.sh --selftest            # render every song, print chart stats and sanity-check the charts
./run.sh --selftest --export   # ...and export the built-in songs as WAV files
./run.sh --selftest --sim      # simulated imperfect player on every chart (checks judging)
./run.sh --selftest --bench    # render cost per frame for each screen
./run.sh --shots <dir>         # screenshot tour (title, select, gameplay, results)
```

## Code map (`src/voidbeat/`)

| File | Role |
|---|---|
| `Main`, `Game` | window, game loop, screen switching, scaling to any window size |
| `AudioEngine` | real-time mixer, hitsounds, latency-compensated smoothed song clock |
| `Synth`, `Arranger` | synthesizer (drums, bass, supersaw lead, plucks, pads, delay, reverb, sidechain) and the procedural composer |
| `ChartGenerator` | turns musical events into charts for each difficulty |
| `CustomSongs` | WAV decoding/resampling, FFT onset detection, BPM and phase estimation |
| `PlayScreen` | gameplay: judging, holds, effects, HUD |
| `TitleScreen`, `SongSelectScreen`, `SettingsScreen`, `ResultsScreen` | menus |
| `Theme`, `Background` | black/purple palette, glow text sprites, animated backdrop and spectrum |

Settings and scores are saved in `~/.voidbeat/`.
