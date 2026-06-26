# Dungeon walls — the inferred-wall algorithm

The `.dungeon` format stores no walls. A level is an **occupancy grid** of micro
cells; each cell references a palette {@link Material} that is either a **floor**
(open) or a **wall** (occupied). The wall *surface* — the smooth, natural-looking
rock the player sees, and the geometry the C ray caster collides against — is
**inferred** from that grid by a per-cell pass. This document is the contract
both the Java editor preview and the Playdate C renderer must implement
identically.

## Grids

- **Micro grid** — the occupancy grid. Width and height are multiples of 5.
- **Macro grid** — `micro / 5`. The party moves one macro cell (5×5 micro) at a
  time. Ladders / holes / portals anchor to a macro cell's **center** micro cell
  `(mx*5 + 2, my*5 + 2)`. Monsters sit on any micro cell and carry a size 1–5
  (micro cells; 5 fills a macro cell).

Render the micro grid with thin lines and the macro grid with thick lines.

## Occupancy

```
o(x, y) = 1 if cell (x,y) is a WALL material, else 0
o(x, y) = 1 for any (x,y) outside the level   // the world is solid rock
```

## Per-cell wall geometry (marching-squares / metaball)

Each **wall** cell is rendered (and collided) **in isolation** from only its four
edge neighbours — so cells across a macro boundary stitch together automatically,
with no global state. For wall cell `(x,y)`:

```
openN = o(x, y-1) == 0
openE = o(x+1, y) == 0
openS = o(x, y+1) == 0
openW = o(x-1, y) == 0
```

A **corner is convex** (and gets rounded) exactly when its two adjacent edges are
both open:

```
convexTL = openN && openW      convexTR = openN && openE
convexBL = openS && openW      convexBR = openS && openE
```

The rounding radius comes from the wall material's **weight** (0–100):

```
r = (1 - weight/100) * 0.5      // in cell units (× cellSize for pixels)
```

- `weight = 100` → `r = 0`: square corners, hard edges — **stone**.
- `weight = 0`   → `r = 0.5`: fully rounded corner — **dirt** flows smooth.

The wall solid for the cell is the unit square with each convex corner replaced by
a quarter-circle of radius `r` whose center is inset `r` from that corner. The
inferred **wall boundary** (drawn dotted purple in the editor) is, for every cell
side that faces an open neighbour, that side trimmed by `r` at convex ends, joined
by the corner quarter-arcs. Non-convex corners stay square, so straight walls stay
straight at every weight — only free corners round.

## C ray caster: point-in-wall test

Everything the ray caster needs is a per-cell **solidity test** in the cell's
local coordinates `(u,v) ∈ [0,1]²`. A point is solid unless it falls in a
rounded-off convex corner notch:

```c
// weight 0..100 for this cell's material; corners from the 4 edge neighbours
float r = (1.0f - weight / 100.0f) * 0.5f;

int solid(float u, float v) {            // u,v in [0,1] within a WALL cell
  if (u < 0 || u > 1 || v < 0 || v > 1) return 0;
  if (convexTL && u < r     && v < r     && hypotf(u-r,     v-r)     > r) return 0;
  if (convexTR && u > 1 - r && v < r     && hypotf(u-(1-r), v-r)     > r) return 0;
  if (convexBR && u > 1 - r && v > 1 - r && hypotf(u-(1-r), v-(1-r)) > r) return 0;
  if (convexBL && u < r     && v > 1 - r && hypotf(u-r,     v-(1-r)) > r) return 0;
  return 1;
}
```

For a DDA/grid ray caster: step cell to cell as usual; when you enter a wall cell,
compute `r` and the four `convex*` flags from that cell's neighbours, then refine
the hit by marching the ray in small steps (or analytically intersecting the
corner arcs) using `solid()`. Because the test only reads the entered cell and its
four neighbours, it is O(1) per cell and needs no precomputed mesh.

## Notes

- Floors are flat; only wall cells contribute geometry.
- The macro grid is purely a movement/feature quantization — the wall inference
  ignores it, which is exactly why 5×5 regions connect to their neighbours
  naturally.
- Keep this file in lockstep with `DungeonEditor.drawWallBlob` /
  `drawBoundary` (Java preview) and the device renderer.
