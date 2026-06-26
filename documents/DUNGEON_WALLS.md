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

## Fill algorithm — chosen per macro cell

Each **macro cell** stores which wall algorithm renders (and collides) its 5×5
region (`Level.macroFill[mx][my]`, serialized as `mfill` rows of `m`/`d`/`s`), so
one map can mix styles. The editor renders each macro clipped to its rectangle:

- **MARCHING** (`m`, the default / original) — *per-cell* rounded blob. Each wall
  cell fills its own square; a corner is rounded (radius `(1 - weight/100)·½`
  cell) only when both its edge neighbours are open. Orthogonal — no diagonal
  bridging. Each cell keeps its own colour.
- **DIAGONAL** (`d`) — the dual-grid marching squares below; infers diagonal lines.
- **SQUARES** (`s`) — plain on/off blocks, one filled square per wall cell;
  **weight is ignored**.

**No colour blending.** MARCHING and SQUARES fill each cell with its own material
colour. A DIAGONAL contour cell spans up to four cells; it uses the single
**majority** material among its occupied corners (ties → scan order), never an
average — so a dirt/stone seam stays crisp instead of going muddy.

## DIAGONAL geometry (dual-grid marching squares)

The surface is the **iso-contour of a dual grid** whose lattice corners are the
cell **centers** (not cell corners). Each *dual square* sits between four adjacent
cell centers and is classified by which of those four cells are walls — the
classic 4-bit marching-squares code. Because the corners are cell centers, a
single wall cell is a small diamond "column", and two wall cells that touch
**orthogonally _or diagonally_** are joined by a band: this is what infers
diagonal lines that a per-corner rounding pass cannot.

For dual square with corner cells `a = o(i,j)` (TL), `b = o(i+1,j)` (TR),
`c = o(i+1,j+1)` (BR), `d = o(i,j+1)` (BL):

```
code = a*8 + b*4 + c*2 + d        // 0..15
```

Crossing points sit at the **midpoints** of the square's sides (`mT,mR,mB,mL`).
The occupied (wall) polygon for each code is the occupied corners plus the
relevant midpoints:

| code(s)        | wall polygon                          | boundary edge(s)        |
|----------------|---------------------------------------|-------------------------|
| 0              | —                                     | —                       |
| 15             | full square `TL TR BR BL`             | none                    |
| 1/2/4/8        | triangle at the one occupied corner   | the mid–mid edge        |
| 3/6/9/12       | quad over the two adjacent corners    | the mid–mid edge        |
| 7/11/13/14     | pentagon (square minus one corner)    | the mid–mid edge        |
| **5 / 10**     | hexagon **connecting the diagonal**   | two mid–mid edges       |

Cases 5 and 10 are the saddle: we **always connect** the two diagonally-occupied
corners (cut the two open corners off), so staggered cells form a continuous
diagonal wall.

### Weight = smoothness

Each boundary (mid–mid) edge is bowed **outward**, away from the occupied
centroid, by the wall material's weight:

```
bow = (1 - weight/100) * MAX_BOW       // MAX_BOW ≈ 0.5 of the half-diagonal
control = midpoint + (midpoint - occupiedCentroid) * bow
```

- `weight = 100` → `bow = 0`: straight chamfers — crisp **stone**, clean diagonals.
- `weight = 0`   → rounded edges — organic **dirt**.

The colour/weight of a dual square is the average over its occupied corner cells
(out-of-bounds corners contribute solid rock, weight 100). The same edges, stroked
dotted purple, are the **inferred wall boundary** shown in the editor.

## C ray caster: point-in-wall test

First read the macro cell's algorithm (`mfill`):

- **SQUARES** — `solid = occ(floor(P.x), floor(P.y))`.
- **MARCHING** — within the cell, solid everywhere except a rounded convex-corner
  notch: for each corner whose two edge neighbours are open, the cell is empty
  beyond a quarter-circle of radius `r = (1-weight/100)·½` centred `r` in from that
  corner (the test that earlier shipped for the per-cell algorithm).
- **DIAGONAL** — the dual-square test below.

The DIAGONAL geometry is local to one dual square — O(1), no precomputed mesh. To
test a point `P` (in cell-center coordinates, so cell `(i,j)`'s center is at integer
`(i,j)`):

```c
int i = (int)floorf(P.x), j = (int)floorf(P.y);   // dual square index
int a = occ(i,j), b = occ(i+1,j), c = occ(i+1,j+1), d = occ(i,j+1);
int code = a*8 + b*4 + c*2 + d;
// local coords u,v in [0,1] within the dual square:
float u = P.x - i, v = P.y - j;
// classify against the case polygon (straight-edge form; the weight bow is cosmetic
// and may be ignored for collision). Half-cell triangles use the lines through the
// side midpoints (0.5):
return point_in_case_polygon(code, u, v);
```

`point_in_case_polygon` is a small switch mirroring the table above (each polygon
is convex, so a handful of half-plane tests suffice; cases 5/10 are the union of
two triangles). A DDA ray caster steps cell to cell, and within each dual square
refines the hit against this polygon. Including the quadratic bow is optional and
only affects how round the silhouette looks, not connectivity.

## Notes

- Floors are flat; only wall cells contribute geometry.
- The macro grid is purely movement/feature quantization — the wall inference
  ignores it, which is why 5×5 regions connect to their neighbours naturally.
- Ladders/holes/portals send the party to a named **target** (`FeatureType.TARGET`,
  any macro cell) **by id**, never by coordinates, so editing the map can't break a
  link.
- `.template` files are **macro-sized rooms** (their micro dimensions are multiples
  of 5; the editor sizes them in macro units) of wall/open/skip cells, drawn with
  the same renderer and **stamped aligned to the macro grid** (origin snapped to a
  macro boundary).

## Regions — runtime-mutable rectangles (hidden doorways)

A level may carry named **regions**: an axis-aligned micro-cell rectangle with an
`on` material index, an `off` material index, and a boolean (default **off**).
Toggling the boolean **repaints the rectangle's cells** to the on/off material —
so a hidden doorway is just a region whose `off` is solid wall and `on` is open
floor.

This keeps the C engine trivial and **mutable at runtime**: store, per region,
`{x, y, w, h, onIndex, offIndex, on}` and a name. Flipping `on` is a small loop
that rewrites those cells in the occupancy grid; the ray caster then sees the new
walls with no other change. The `name` lets game logic reference the boolean
(e.g. a lever sets `regions["secret-door"].on = true`).

Serialized as `region name=… x=… y=… w=… h=… on=<idx> off=<idx> state=<bool>`.

## Doodads

A micro cell may hold up to **three doodads** — small decorative/interactive
objects, each `{id, dir}`. On placement the facing is **inferred**: the first
<em>open</em> (floor) neighbour scanning **clockwise from north** (N, E, S, W), so
a torch on a wall faces the corridor it borders; ties resolve to the earliest in
that order, and a fully-walled cell defaults to N. The direction can be overridden
in the inspector. Serialized as `doodad x=… y=… id=… dir=n|e|s|w`.

- Keep this file in lockstep with `mg.editor.dungeon.WallRenderer` (shared by the
  dungeon and template editors) and the device renderer.
