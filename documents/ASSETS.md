# ASSETS: monster art requirements

This note defines the art a `.monster` references and how the editor/codegen
expect it to be laid out. The goal is the **smallest set of animations that
still reads as a living creature** in both views the game uses, on Playdate's
unusual display.

## The display you are drawing for

- **400 × 240, 1-bit.** Every pixel is black or white — no grey. Shading is
  done with dithering (ordered/Bayer patterns). Author at final pixel size; do
  not rely on scaling.
- The Playdate SDK animates from **image tables** (sprite sheets): one image per
  frame, addressed by index. A `.monster` points at one sheet per animation, and
  the frames inside it are played in order.
- Keep frame counts low. These animations loop on a handheld; 2–6 frames per
  state is plenty and keeps both flash size and RAM down.

## The two views

Every monster needs **two** independent art sets, because the same creature is
drawn two completely different ways:

| View      | Used in                         | Camera        | Typical footprint |
|-----------|---------------------------------|---------------|-------------------|
| `jrpg`    | 2D turn-based battle screen      | side / 3⁄4    | up to ~128×128 px |
| `dungeon` | first-person 3D dungeon crawl    | facing player | up to ~160×160 px, scaled by distance |

These map directly to the two `art` blocks in a `.monster` file:

```
art view=jrpg    idle=... walk=... stance=... attack=...
art view=dungeon idle=... walk=... stance=... attack=...
```

A view may leave any slot empty (`""`) — e.g. a stationary boss might skip
`walk`. The game falls back to `idle` when a requested animation is missing.

## The four animation states

These are the only states the editor asks for. They cover the whole combat loop
without over-producing art.

1. **idle** — the resting loop, shown most of the time. Subtle: breathing, a
   tail flick, a flame flicker. **2–4 frames**, ping-pong friendly.
2. **walk** — locomotion. In `jrpg` this is the approach/advance; in `dungeon`
   it is the "monster shuffles toward you" loop before it strikes. **2–6 frames**,
   designed to loop seamlessly.
3. **stance** — the fighting/ready pose: weapon raised, claws out, the "about to
   act" hold. Held while the monster is the active combatant or telegraphing.
   **1–3 frames** (often a short loop or a single held pose).
4. **attack** — the strike. This one is a one-shot, not a loop: it plays once and
   returns to `stance`/`idle`. Put the contact/impact on a specific frame so the
   game can sync damage to it. **3–6 frames**.

> Rule of thumb: `idle` and `walk` loop; `stance` holds; `attack` plays once.

## Sheet format

- One **horizontal strip** per animation: frames left-to-right, all the same
  cell size, no padding between them. (This matches a Playdate image table built
  with a `name-table-<w>-<h>.png` naming convention, or a sliced strip.)
- Filenames are referenced **relative to the `.monster` file**; the editor stores
  them that way when you browse for them.
- Suggested naming, so a set is obvious on disk:

  ```
  slime/
    jrpg/    slime-idle.png  slime-walk.png  slime-stance.png  slime-attack.png
    dungeon/ slime-idle.png  slime-walk.png  slime-stance.png  slime-attack.png
  ```

- Transparency: use the SDK's image alpha (1-bit mask) so the silhouette reads
  cleanly against any background. Anchor every frame on the **same baseline**
  (feet/ground line) so the creature does not jitter between frames.

## Authoring checklist

- [ ] Both `jrpg` and `dungeon` sets present (or intentionally empty slots).
- [ ] 1-bit, final size, dithered shading — no anti-aliased greys.
- [ ] idle/walk loop cleanly; attack reads as a single strike with a clear impact
      frame.
- [ ] Consistent baseline/anchor across all frames of a state.
- [ ] Frame counts kept small (≤6) to bound flash + RAM.

## How this connects to codegen

The `.monster` file stores only **paths and frame intent**, never pixels. When
the build runs (see [CODEGEN.md](CODEGEN.md)), these paths resolve to Playdate
image tables packed into the `.pdx` bundle; the monster's *numeric* design (the
level/stat table and skills) is what may flow into the SPINE document. Art is
static content and never enters the save file — only references to which monster
is where, and its runtime stats, do.
