# AI.GEN: choosing an image-generation backend for B&W sprites + animation

This note surveys how to **directly** integrate AI image generation for the asset
pipeline, optimised for the two things this project actually needs: **1-bit
black-and-white art** and **animation** (directional walk cycles, item-use
effects). It exists because Meshy works today but is almost certainly a *proxy*:
its `nano-banana` model **is Google's Gemini 2.5 Flash Image** ([Google's codename
is "nano banana"](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-image)),
so we are paying a markup to reach a model we could call directly. The question
is whether to go direct, and to which backend.

See [ASSET_PIPELINE.md](ASSET_PIPELINE.md) for the pipeline these feed; the
existing client is `mg.assets.Meshy` and everything is forced to 1-bit by
`mg.assets.Mono`.

## Two truths that reframe the decision

**1. Black-and-white is always post-processing — never pick a backend "for B&W."**
No image API outputs 1-bit; they all return full-colour or greyscale PNGs. We
*always* convert with `Mono` (threshold or Bayer dither, later Floyd/Atkinson).
So "good B&W support" really means: *does the model produce clean, high-contrast
shapes with readable silhouettes that survive dithering at small sizes, and does
it obey "black and white, 1-bit, pixel art, flat shading" prompts?* Every capable
model can; this is not the differentiator.

**2. Animation here is a consistency problem, not a video problem.**
We don't want video — we want a handful of **consistent frames** (a 4-frame walk
strip) and **consistent views** (front/back/left/right) of the *same* creature.
The hard part is character/style consistency across frames and angles. That is
exactly where models differ, and it is the differentiator. Two families solve it:

- **Reference-conditioned editing** (give a reference image, ask for "same
  creature, side view, mid-stride"). This is what we already do via Meshy's four
  `image-to-image` calls.
- **Native sprite/animation models** that emit directional sheets and walk cycles
  in one call, purpose-built for game sprites.

## The contenders

### A. Purpose-built pixel-art / sprite APIs — *closest fit*

These are built for exactly our problem: grid-aligned pixel art, limited palettes,
directional rotation, and walk/attack animations.

- **PixelLab** — has `POST /v2/create-character-with-4-directions` (south / west /
  east / north), 4- or 8-direction rotation for top-down/iso/side games, and
  skeleton- or text-driven animation (walk/run/attack). This maps almost 1:1 onto
  our `overworld` walk set and item-use animations.
  ([pixellab.ai/pixellab-api](https://www.pixellab.ai/pixellab-api))
- **Retro Diffusion** — `rd-animation` model with a `four_angle_walking` style,
  emitting sprite sheets with consistent framing and low frame counts that match
  game engines; API plus availability on Replicate.
  ([retrodiffusion.ai](https://retrodiffusion.ai/),
  [on Replicate](https://replicate.com/blog/retro-diffusions-pixel-art-models-are-now-on-replicate))

**Why they win for us:** they produce directional views and walk animations
*natively and consistently*, instead of us orchestrating four edits and praying
they match. Output is already pixel-art and limited-palette, so it dithers to
1-bit cleanly. The downside is they're specialised vendors (smaller, less general)
— but our use case is exactly their lane.

### B. General multimodal editing models — *flexible, you orchestrate*

- **Google Gemini 2.5 Flash Image ("nano-banana")** — strong character
  consistency, multi-image blending, conversational editing; ~**$0.039/image**;
  the model Meshy proxies. Going direct removes the markup and gives full control.
  ([model docs](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-image),
  [pricing](https://ai.google.dev/gemini-api/docs/pricing))
- **FLUX.1 Kontext** (Black Forest Labs) — generation + editing in one model with
  strong multi-edit character consistency, faster and cheaper than gpt-image-1;
  ~**$0.04 (pro) / $0.08 (max)** per image via fal — and **FLUX.1 Kontext-dev has
  open weights**, so it can be self-hosted later.
  ([bfl.ai](https://bfl.ai/models/flux-kontext),
  [HF weights](https://huggingface.co/black-forest-labs/FLUX.1-Kontext-dev))
- **OpenAI gpt-image-1** — capable editing, but weaker at keeping consistency on
  user-uploaded references (BFL's own comparison calls out gpt-image-1's struggle
  with face/character consistency). Meshy also exposes it (`gpt-image-2`).

**Why they're plausible:** maximum flexibility and quality; the reference→variation
flow we built for Meshy ports directly. **Why they're not ideal alone:** *we* own
the directional/animation orchestration and frame-consistency tuning, which is the
hard part the specialised tools already solved.

### C. Open-weights / self-host — *control + zero per-image cost*

- **FLUX.1 Kontext-dev** and **Stable Diffusion (SD3.5/SDXL) + pixel-art LoRAs**
  via ComfyUI. No per-image fee, full determinism (seeds), offline, and pixel-art
  LoRAs are excellent for this aesthetic. Cost is operational: you run a GPU and
  maintain a workflow. Best **long-term** if volume or control grows.

### D. Aggregators — *one API, many models*

- **Replicate / fal / DeepInfra** host FLUX, Retro Diffusion, SD and others behind
  one HTTP API. Good for A/B-ing backends without writing five clients, at a small
  markup over raw.

### E. Meshy (current) — *convenience proxy*

Convenience layer (multi-view, pose presets, task management) over — almost
certainly — Google's model. Fine as the bootstrap adapter; you pay a markup and
cede control. Keep it working while we evaluate; don't make it load-bearing.

## At a glance

| Backend | Pixel/B&W fit | Animation + consistency | Native directions? | Cost (≈) | Self-host | Best for |
|---|---|---|---|---|---|---|
| PixelLab | excellent | native walk/attack, skeleton | **yes (4/8)** | credits | no | our exact case |
| Retro Diffusion | excellent | native sprite sheets | yes (4-angle) | credits / Replicate | no | walk-cycle sheets |
| Gemini 2.5 Flash Image | good | strong (reference edit) | no (orchestrate) | ~$0.039/img | no | direct, drop Meshy markup |
| FLUX.1 Kontext | good | strong (multi-edit) | no (orchestrate) | ~$0.04–0.08/img | **dev weights** | quality + future self-host |
| OpenAI gpt-image-1 | good | weaker on refs | no | higher | no | already-OpenAI shops |
| SD + LoRA (ComfyUI) | excellent | with workflow | with workflow | GPU only | **yes** | volume / full control |
| Meshy (now) | good | our 4-edit flow | via multi-view (3) | markup | no | bootstrap |

## How each maps to our pipeline

The pipeline is: **reference → directional/animation frames → slice → 1-bit →
hand cleanup → step-through verify** (see ASSET_PIPELINE.md).

- **PixelLab / Retro Diffusion** collapse "reference → 4 directional walk strips"
  into one or two native calls with built-in consistency, then we just
  `Mono.region`-slice and dither. Fewer calls, fewer mismatches.
- **Gemini / FLUX / gpt-image** keep our current shape: one text-to-image
  reference, then N reference-conditioned edits per view/frame. More control over
  prompt, more orchestration on us.
- **All** end at `Mono` for 1-bit — the backend choice never changes the 1-bit
  invariant or the storage format.

## Recommendation

1. **Don't rip out Meshy.** It works and unblocks you now.
2. **Introduce a provider abstraction.** Generalise `mg.assets.Meshy` into an
   `ImageGen` interface — `textToImage(prompt)`, `edit(refs, prompt)`, and
   optionally `directions(ref)` / `animate(ref, action)` — with one implementation
   per backend, selected in `Settings`. This makes the rest of this doc a config
   choice instead of a rewrite, and keeps the 1-bit funnel (`Mono`) shared.
3. **Evaluate in this order for *this* project:**
   1. **PixelLab** — its 4-direction + walk-animation endpoints are the nearest
      thing to our exact requirement; try it first.
   2. **Retro Diffusion** — sprite-sheet/`four_angle_walking` animations.
   3. **Gemini 2.5 Flash Image direct** — removes the Meshy markup, best
      consistency among the general models, and confirms/replaces the proxy.
   4. **FLUX.1 Kontext** — strongest open-weights path; pick this if self-hosting
      to kill per-image cost becomes attractive.
4. **Keep B&W out of the backend decision.** Pick on consistency/animation/control;
   `Mono` owns 1-bit regardless.

## First experiment to run (build experience)

On a single monster: generate the **same** creature with (a) the current Meshy
reference→4-edit flow and (b) **PixelLab's `create-character-with-4-directions`**,
then push both through `Mono` dithering at the target cell size and step through
the frames. Judge: directional consistency, silhouette readability at 1-bit, and
how much hand cleanup each needs. That comparison will tell us more than any spec
sheet — and it's the kind of requirement this project should let real usage drive.
