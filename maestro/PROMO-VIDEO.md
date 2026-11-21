# Promo videos

The promo videos in `design/promo/` place a Maestro screen recording inside a static Sidephone
render, crop to 4:9 and tile four panels into 16:9. Rebuild everything with:

```sh
design/promo/build-videos.sh
```

## Source image

`design/promo/sidephone.webp` is an **AVIF** file saved with a `.webp` extension, with alpha as a
second AV1 stream. ImageMagick has no AVIF delegate here, and ffmpeg would write the colour plane
only (transparent pixels come out black). Merge the planes once:

```sh
ffmpeg -i design/promo/sidephone.webp \
  -filter_complex "[0:v:0]format=rgb24[c];[0:v:1]format=gray[a];[c][a]alphamerge" \
  -frames:v 1 -update 1 design/promo/sidephone.png
```

`sidephone.png` (1000×1000 RGBA) is the canonical still; the script keeps it in sync.

## Geometry

Measured from `sidephone.png`:

| | |
| --- | --- |
| phone bounds (alpha > 0, includes shadow) | x 315…687, y 69…931 |
| display (dark glass) | x ≈ 339…666, y ≈ 78…551 |
| video overlay, as rendered | 305×406 at (348, 117) |
| 4:9 crop | 432×972 at (284, 14) |

The 480×640 recording (3:4) is scaled to 305×406 and sits inset in the glass. Composite at 1× and
encode on the GPU (`h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0`): compositing on an
upscaled canvas and encoding with x264 costs minutes for no visible gain.

## One video

Loop the flattened still, overlay the recording, end with the recording (`shortest=1`). The
recordings are variable frame rate (`adb screenrecord`, ~13 fps), so `fps=30` normalises them.

```sh
flat=/tmp/sidephone-flat.png
magick design/promo/sidephone.png -background white -flatten -alpha off "$flat"

ffmpeg -loop 1 -framerate 30 -i "$flat" -i maestro/recordings/01-browse-play.mp4 \
  -filter_complex "\
    [0:v]format=yuv420p[bg];\
    [1:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[fg];\
    [bg][fg]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[v]" \
  -map "[v]" -an \
  -c:v h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -maxrate 40M \
  -pix_fmt yuv420p -r 30 -movflags +faststart \
  design/promo/4x9/01-browse-play.mp4
```

`-an` drops the recordings' `data` stream (touch events). Pitfalls: `overlay` takes timing from
its first input (loop the still, or use `eof_action=repeat`); `trim` does not reset PTS (add
`setpts=PTS-STARTPTS`); `accurate_rnd` is a scale *flag*; the still is flattened onto white
because MP4 is opaque.

## 4:9 panels and the 16:9 grid

432×972 is exactly 4:9 and removes the white space around the phone; four of them are exactly
16:9 (`4 × 432 = 1728`, `1728 / 972 = 16/9`), so the grid needs no padding. Each panel is padded
from its last frame to `CLIP_SECONDS = 40` with `tpad=stop_mode=clone`, so all four phones end
together:

```sh
R=maestro/recordings
ffmpeg -loop 1 -framerate 30 -i "$flat" \
  -i $R/01-browse-play.mp4 -i $R/02-search-library-writes.mp4 \
  -i $R/03-queue-transport.mp4 -i $R/04-history-settings.mp4 \
  -filter_complex "\
    [0:v]format=yuv420p,split=4[b0][b1][b2][b3];\
    [1:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f1];\
    [2:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f2];\
    [3:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f3];\
    [4:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f4];\
    [b0][f1]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c1];\
    [b1][f2]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c2];\
    [b2][f3]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c3];\
    [b3][f4]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c4];\
    [c1][c2][c3][c4]hstack=inputs=4,format=yuv420p[v]" \
  -map "[v]" -an \
  -c:v h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -maxrate 60M \
  -pix_fmt yuv420p -r 30 -movflags +faststart \
  design/promo/sidetrack-suite-16x9.mp4
```

## Gallery GIF

The 16:9 grid is sampled into a 15 fps GIF in two passes: `stats_mode=diff` spends the 256-colour
table on what changes, and bayer dithering with `diff_mode=rectangle` keeps the flat chrome flat.

```sh
ffmpeg -i design/promo/sidetrack-suite-16x9.mp4 \
  -vf "fps=15,palettegen=max_colors=256:stats_mode=diff" /tmp/palette.png
ffmpeg -i design/promo/sidetrack-suite-16x9.mp4 -i /tmp/palette.png \
  -lavfi "fps=15,paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  -loop 0 design/promo/sidetrack-suite-16x9.gif
```

## Outputs

| File | Notes |
| --- | --- |
| `design/promo/sidephone.png` | 1000×1000 RGBA still, decoded from the AVIF |
| `design/promo/4x9/01…04-*.mp4` | 432×972 4:9 panels, 30 fps, 40 s each |
| `design/promo/sidetrack-suite-16x9.mp4` | 1728×972 grid, 30 fps, 40 s |
| `design/promo/sidetrack-suite-1920x1080.mp4` | 1920×1080 lanczos upscale of the grid |
| `design/promo/sidetrack-suite-16x9.gif` | 1728×972, 15 fps, 40 s palette GIF |

All encodes are H.264 High, `yuv420p`, `+faststart`, cq 19.
