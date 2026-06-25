# ASSET PIPELINE: black-and-white images, PixelLab, and resizing

This note defines the asset pipeline: how images are generated, stored, edited,
and resized for the Playdate's 1-bit display. It is the umbrella over
[ASSETS.md](ASSETS.md) (monster art intent) and [CODEGEN.md](CODEGEN.md) (how
content reaches the device).

## The one hard rule: everything is a 1-bit PNG

The Playdate display is **400×240, 1-bit** — every pixel is black or white, no
grey (shading is dithering). So every image in a project is:

- **PNG format**, always.
- **1-bit black and white** — literally two colours.

This is enforced in code by `mg.assets.Mono`, the single chokepoint for image
data. It produces and consumes indexed `BufferedImage`s on a three-entry palette
— index 0 = white/paper, 1 = black/ink, 2 = **transparent** (a real alpha entry,
so it writes a genuine PNG with transparency; the editor renders it green).
Pixels are addressed via `state(...)`/`setState(...)` (`WHITE`/`BLACK`/
`TRANSPARENT`); `isBlack`/`set` remain for the common B&W case. Convert
colour/grey sources with `mg.assets.Dither.apply(src, algo, threshold)`
(or the 4-arg form with an `AlphaMode` of white/black/transparent),
which offers a full set of algorithms (`Mono.toMonoThreshold`/`toMonoDither`
remain as simple shortcuts):

- **Threshold** — hard luminance cut; flat and clean.
- **Ordered, dispersed-dot (Bayer 2/4/8)** — fast, deterministic, tileable.
- **Ordered, clustered-dot (halftone 4×4)** — newsprint-style dots that survive
  downscaling better than dispersed patterns.
- **Error diffusion** — Floyd–Steinberg, Atkinson, Jarvis–Judice–Ninke, Stucki,
  Burkes, Sierra, Sierra Lite; serpentine scan for the smoothest gradients
  (Floyd–Steinberg is the default).
- **Contour (marching squares)** — traces the iso-line between dark and light
  and draws clean outline curves; ideal for crisp line-art silhouettes.

The `threshold` (0–255) is the quantisation midpoint: black below it for
threshold/diffusion, a bias for ordered modes, the iso-level for contour.

## API keys

Both AI services are configured in **Settings ▸ Grok API Key…** and persisted by
`mg.editor.Settings` to `settings.properties` in the per-user config dir
(owner-only on POSIX):

- **Grok (xAI)** — text assistance.
- **PixelLab.ai** — pixel-art image generation (see [AI.GEN.md](AI.GEN.md) for
  why this backend).

## PixelLab integration

`mg.assets.PixelLab` wraps the PixelLab REST API (base
`https://api.pixellab.ai/v1`, bearer auth). Calls are **synchronous** — each
request returns the generated image(s) directly as base64 PNG (no polling):

| Purpose | Method | Endpoint |
|---|---|---|
| Text → image (reference) | `pixflux` | `POST /generate-image-pixflux` |
| Text + style reference | `bitforge` | `POST /generate-image-bitforge` |
| Re-face a character | `rotate` | `POST /rotate` |
| Reference + action → frames | `animateWithText` | `POST /animate-with-text` |
| Reference + skeleton → frames | `animateWithSkeleton` | `POST /animate-with-skeleton` |

`PixelLabGen` runs these off the UI thread with a progress window.

### Monster workflow

Monsters are built from explicit files; **references are the high-fidelity
domain** and live in a `ref/` subfolder next to the `.monster`:

1. **Reference** — `Generate` (pixflux) a full-colour `.ref.png`; refine with
   `Style onto` (bitforge), `Rotate` (a new facing), or `Animate` (action →
   frames).
2. **Extract → B&W** — drag-select a region of a reference, resample to the
   target size, and convert with a chosen algorithm + live preview
   (`ReferenceExtractDialog`). Two transparency controls:
   - **transparent px** — how a (mostly) transparent source pixel is treated:
     flatten to **white** / **black**, or **preserve** it as transparent.
   - **background** — *edge scan* infers the background (white reachable from the
     border without crossing ink) and turns it transparent. In the preview,
     transparent shows **green** and detected background shows **blue**.
   The output is a real frame file (1-bit + a transparent alpha entry). A
   **Whole image** button resets the crop to the entire reference, and the
   last-used settings (size / algorithm / threshold / transparency / background)
   are remembered in the `.monster` (`Monster.extract`) to reseed the next extract.
   Extracted frames default into an `ext/` subfolder to stay tidy (beside `ref/`).
3. **Assemble** — select frame files into the monster's animations: **battle**
   (stance image, damaged-stance image, attack animation) and **dungeon** (idle
   and walk, each in four orientations: towards / away / left / right).
4. **Verify** — play the animation in the panel to check timing and baseline.

**Skeleton walk** (`Skeleton…` → `SkeletonAnimateDialog`) drives
`animate-with-skeleton` for a Wizardry-style crawler: pick a viewer direction
(towards / away / left / right → PixelLab south / north / west / east at the
`side` view), a frame count and output size, then **drag the skeleton joints** to
pose each frame (scrub between frames; `Reset to template` reseeds a walk gait).
The skeleton (`mg.assets.Skeleton`, the 18 PixelLab joint labels) sets the
per-frame pose; the direction sets facing. The no-hand-posing flow is **Estimate
from image** (calls `/estimate-skeleton` to detect the joints — one rest pose;
coordinate space is auto-detected, normalized-vs-pixel) → **Animate walk**
(`Skeleton.walkFromRest` swings the limbs around the detected joints into a walk
cycle). `/estimate-skeleton` itself returns only a single pose, not an animation. For a fully no-skeleton path, use **Animate…** (`animate-with-text`): a reference
+ an action verb ("walk"), with a **follow-reference** strength
(`image_guidance_scale`, 1–20; default raised to ~6 because PixelLab's own 1.4
default ignores the reference and invents from the text). The posed skeleton is **cached in the
`.monster`, keyed to the reference image** (`Monster.skeletons` → `SkeletonData`,
normalized 0..1 so it is resolution-independent), so edits persist across
sessions. Generating sends the posed frames (with `inpainting_images`/
`mask_images` null-arrays matching the frame count, which PixelLab requires) and
saves the colour results to `ref/` to extract to B&W.

### Item assets

Each item has **three icon images** (one per project icon size — large/medium/
small, from `.project`) and a **usage animation** (a frame-file list with speed
and loop). Everything is selected explicitly; nothing is generated or resized in
the item editor.

## The resize algorithm (exact, deterministic, 1-bit)

1-bit art can't blend on downscale, so we reduce by integer factors with a
**2×2 box vote**. This is `Mono.reduceHalf(img, threshold)`:

> Map every non-overlapping 2×2 block of the source to one output pixel. Count
> the black pixels in the block (0–4). The output pixel is **black iff that count
> is ≥ `threshold`.**

`threshold` encodes the visual intent:

| Threshold | Constant | Rule | Effect |
|---|---|---|---|
| 1 | `PRESERVE_INK` | any black → black | keeps thin lines/silhouettes; thickens slightly |
| 2 | `MAJORITY` | tie or majority → black | balanced, density-preserving **default** |
| 3 | `PRESERVE_PAPER` | strong majority → black | thins ink, keeps highlights |

**Quarter-size is just two halvings:** `reduceQuarter(img, t)` ≡
`reduceHalf(reduceHalf(img, t), t)`. There is no separate quarter algorithm, so
behaviour is predictable: 48 → 24 → 12.

**Edge handling:** out-of-bounds pixels read as white (paper), so blocks on a
right/bottom edge of an odd-sized image simply vote with fewer pixels; output
dimensions round up (`ceil(n/2)`). Authoring at multiples of 4 avoids this.

**Worked example (one 2×2 block, `MAJORITY`):**

```
source 2x2     blacks   output (>=2 ?)
■ ·             2        ■ black
· ·

■ ·             1        · white
· ·

■ ■             3        ■ black
■ ·
```

Pick the threshold per asset: outlined icons usually want `PRESERVE_INK` so the
outline survives at 12×12; filled/shaded sprites usually want `MAJORITY`.

## Project settings (`.project`)

A `.project` file in the project root (KV format, managed via **Settings ▸
Project Settings…**) holds conventions the tooling can't guess:

- `icon_size` — square edge of item icons (px).
- `anim_cell_w` / `anim_cell_h` — animation frame cell size (px).

`mg.editor.ProjectSettings.current()` exposes these; the image editor's
`→ icon` / `→ cell` buttons scale to them.

## Editing tools (black-and-white only)

Because everything is 1-bit, the in-editor tools are deliberately simple and all
route through `Mono`, so the 1-bit invariant holds end to end. The `.png` editor
(`BwImageEditor`) provides:

- **Paint** — pencil / eraser / flood fill, invert, clear; zoom; an **animation
  stepper** (frames / fps, step / play) that slices the image as a horizontal
  strip to validate timing and alignment. A 5-deep **Undo** (button or Ctrl/Cmd-Z)
  rolls back the last transforms/strokes — each paint stroke is one step.
- **Resize** — ½ / ¼ box-vote reduce (threshold selectable), `Resize…` (scale to
  an arbitrary size by area vote), `→ icon` / `→ cell` (scale to the `.project`
  sizes).
- **Canvas** — `Canvas…` resizes the canvas (content centred, clipped) without
  scaling; `Trim white` / `Trim black` crop a uniform margin to the content
  bounding box.
- **Crop region…** — a drag-to-select popup (`RegionSelectDialog`) to pull a
  frame or sub-rect out of a sheet when the automatic tooling is wrong.
- **Import…** — bring in any image, converted to 1-bit by a chosen algorithm
  (threshold, ordered/Bayer, halftone, error diffusion, or contour).

Files can be **cloned** and **deleted** from the project tree (right-click), and
any load failure shows an in-pane "failed to load" panel with the error; the
**View ▸ Log** window keeps the last 1000 actions and exceptions.

## Image transforms (pure Java)

Transforms run **in-process, pure Java** — no external binary — so the editor is
identical on every OS and previews are instant. Today that means the `Mono` ops
above (threshold, Bayer dither, ½/¼ reduce, scale, canvas, trim, region) plus
the paint tools. Richer filters (Floyd–Steinberg / Atkinson dithering, unsharp,
edge detection) are intended to be added directly on `BufferedImage` via Java2D
(`ConvolveOp`, `Graphics2D`) **as real authoring use demands them**, rather than
up front.

An earlier ImageMagick (`convert`) integration was removed: shelling out to a
native binary is not reliably cross-platform (the Windows `convert.exe` clash,
plus the install requirement), and a Java binding like im4java still requires
ImageMagick installed. `tool-png-to-pbm.sh` remains as a **standalone** shell
helper for device-side PBM (P4) export, separate from the editor.
