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

The geometry is local to one dual square — O(1), no precomputed mesh. To test a
point `P` (in cell-center coordinates, so cell `(i,j)`'s center is at integer
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
  link. `.template` files are reusable wall/open/skip stamps drawn with the same
  renderer.
- Keep this file in lockstep with `mg.editor.dungeon.WallRenderer` (shared by the
  dungeon and template editors) and the device renderer.
