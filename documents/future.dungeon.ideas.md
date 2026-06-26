# Future dungeon ideas — 20 mechanics we're missing

The model is expressive (occupancy grid, macro/micro, inferred walls, doors,
regions, doodads, features, monster placements) but **structurally quiet** — it
can describe a *shape* far better than it can describe an *experience*. This is a
shopping list of gameplay verbs proven in other grid crawlers (Wizardry, Dungeon
Master, Eye of the Beholder, Etrian Odyssey, Might & Magic, Legend of Grimrock),
chosen because each one (a) is *missing* today, (b) lands cleanly on the existing
grid + the DVM (`documents/design.dvm.md`), and (c) earns its keep under this
game's hard rule: **you enter a dungeon once, you cannot leave, and you either die
or clear it** (see `documents/level.design.theory.md`). That rule makes attrition,
commitment, and legibility the load-bearing concerns, so the list leans that way.

Each entry: what it is · the model hook (grid + DVM ops + SPINE overlay) · the
editor surface · why it matters for designing levels. Existing primitives we are
*not* re-listing: doors, hidden-doorway regions, ladders/holes/portals/targets,
doodads, static monster placement.

Legend for the model hook: **op** = a new/extended DVM op or script verb; **bit**
= a mutable SPINE overlay slot; **mat** = a `Material` property; **doodad** = a new
interactive doodad kind.

---

## A. Navigation & disorientation (the no-automap tax)

**1. Spinners / facing-scramblers.** *(Wizardry, Bard's Tale.)* A macro cell that
silently rotates the party's facing on entry, so the corridor you thought ran
north now runs east. The cruelest, cheapest disorientation in the genre.
· *Hook:* doodad `spinner{turn: cw|ccw|180|random}`, fires `on-enter`; facing is a
SPINE field, so it's just a `SET_FLAG`-style write. · *Editor:* a spinner doodad
with a turn dropdown; the canvas draws the rotation glyph. · *Why:* defeats
lazy mapping and makes landmarks (idea 18) matter; use sparingly and *fairly*
(telegraph with a visual motif) given no re-entry.

**2. Teleport tiles & teleporter mazes.** *(Wizardry, Grimrock.)* Step on a tile,
appear on another — sometimes visibly, sometimes not. A whole "room" can be a knot
of teleports. · *Hook:* this is the existing `PORTAL` feature generalized from
macro-center to *any* micro cell, plus a "silent" flag (no transition effect). The
`TARGET`-by-id linking already exists. · *Editor:* allow portals on micro cells;
add a `silent` toggle. · *Why:* space that doesn't obey adjacency is the genre's
signature puzzle; pairs viciously with spinners.

**3. Forced-movement floors — conveyors & ice slides.** *(Grimrock, Pokémon ice
caves.)* A tile that pushes the party one cell per step in a fixed direction, or
ice that slides you until you hit a wall. · *Hook:* `mat.flow = dir` on a floor
material (conveyor) or `mat.slick = true` (slide); the mover applies it after each
step. No new geometry. · *Editor:* two new material properties + arrow overlay on
those cells. · *Why:* turns plain floor into a navigation puzzle and a delivery
mechanism (push the party into a hazard or across a gap).

**4. Pits, trapdoors & multi-floor shafts.** *(Eye of the Beholder, Dungeon
Master.)* You already have `HOLE`; make it a *deliberate vertical connectivity
language*: hidden pits that drop you a floor (damage + shortcut), one-way drops
that prevent backtracking, and aligned shafts that fall *through* several floors.
· *Hook:* extend `HOLE` with `{hidden, fallTo: levelIndex, damage}`; landing target
is a `TARGET` on the lower level. · *Editor:* hole inspector gains hidden/target/
damage. · *Why:* vertical level design + reinforces the one-way descent the
no-re-entry rule wants.

---

## B. Switches, logic & locks

**5. Pressure plates & multi-input logic gates.** *(Dungeon Master, Grimrock.)* A
plate that holds a circuit while something stands on it; combined with levers, a
door that opens only on `(AND plateA leverB (NOT leverC))`. · *Hook:* plate doodad
raises `on-enter`/`on-exit`; the DVM script's `AND/OR/NOT` + `JZ` already express
the logic; door/region driven by a guarded rule. · *Editor:* a plate doodad; the
logic itself is authored as a script rule (see the DVM doc) — the editor's job is
to make naming switches/doors easy. · *Why:* the backbone of every non-combat
room; the script VM was built for exactly this.

**6. One-way doors & portcullises that seal behind you.** *(Souls-likes, classic
crawlers.)* A gate that slams shut once you pass and cannot be reopened from this
side. · *Hook:* a `Door` `lock=event` whose own `on-enter`/`on-interact` rule
`(close-door self)` and clears the openable affordance; or a door flag
`oneWay=behind`. · *Editor:* a "seals behind" checkbox on the door inspector. ·
*Why:* this is the **micro-scale version of the whole game's macro rule** — it
teaches commitment cheaply and shapes the dungeon into a forward-only tree.

**7. Illusory walls & search-to-reveal secrets.** *(Wizardry, M&M.)* A wall that
renders solid but is passable (walk through it), or a secret only found by
*searching* a suspicious cell. · *Hook:* the inverse of today's hidden-doorway
region — a `region` whose `on` is wall-looking-but-passable, or a `phantom` flag
on a wall material that the mover treats as open. Searching = an `on-interact` that
flips a `bit`. · *Editor:* a "phantom" material property; a "secret" region mode. ·
*Why:* rewards curiosity and careful play; the optional-vault economy (idea 17)
hangs off it.

**8. Sequence / password / riddle gates.** *(Wizardry, Etrian.)* A door that opens
only after levers are pulled in order, or a number/word is entered, where the clue
lives in the world (a mural, a `.story` beat). · *Hook:* a counter `bit` advanced
by `on-interact` rules; a guard checks the sequence; password input is a small UI
op. Clues link to `.story` (idea 19) and the story VM. · *Editor:* author the rule;
mark the clue doodads. · *Why:* the first mechanic that makes *lore* mechanically
load-bearing, bridging dungeon ↔ story.

---

## C. Hazards & resource attrition (the heart of "no re-entry")

**9. Damaging / altering floor materials.** *(Lava, poison bogs, deep water.)* Your
palette already has `lava`/`water`/`grass` as *cosmetic* floor. Give floors runtime
effects: damage-per-step, slow, drown-without-swim, douse-your-torch. · *Hook:*
`mat.hazard = {dmg, slow, deep, ...}`; applied by the mover on enter. · *Editor:*
hazard fields on the material form. · *Why:* converts terrain into an attrition
tax — central when you can't leave to heal.

**10. Darkness & a light economy.** *(Dungeon Master, Grimrock.)* Sight is a radius;
torches and light spells are a *consumable resource*; some regions are pitch black.
· *Hook:* a `region.dark` property + a party `light` SPINE field ticked by
torch/oil items; the 1-bit renderer dims/curtains beyond the radius. · *Editor:* a
"dark" region toggle. · *Why:* the purest survival-resource pressure; turns "do I
spend a torch to read that inscription" into a decision.

**11. Anti-magic / curse / silence zones.** *(Wizardry's anti-magic squares.)* A
region that disables spells, drains MP, blocks teleport/recall, or applies a curse
while inside. · *Hook:* `region` flags (`noMagic`, `mpDrain`, `noTeleport`) read by
the combat/ability layer. · *Editor:* region property checkboxes. · *Why:* forces
the party off its dominant strategy in a bounded space — a designer's pressure
valve, and a natural boss-arena modifier.

**12. Camps / rest points & the no-safe-rest rule.** *(Roguelike descents,
Souls.)* Because you can't exit to heal, *internal* rest is the only relief — and it
must be scarce, risky, or limited (rest invites ambush; only N rests per run).
· *Hook:* a `campfire` doodad that runs a rest routine, gated by a per-run counter
`bit`; optionally raises an ambush event. · *Editor:* a campfire doodad with a
"uses" field. · *Why:* this is the metronome of the whole attrition design — where
the player is *allowed* to breathe defines the dungeon's pacing.

**13. Traps — darts, gas, alarms, disarmable.** *(Every crawler.)* A trapped cell
that fires on enter: damage, status, or an *alarm* that summons monsters. Some are
detectable/disarmable by a skill check. · *Hook:* `trap` doodad
`{effect, detectDC, armed: bit}`; `on-enter` guarded by `armed`. · *Editor:* trap
doodad with effect + difficulty. · *Why:* punishes careless movement and rewards
the "scout" role; the alarm variant ties traps to encounters (idea 14).

---

## D. Encounters that breathe

**14. Spawners, patrols & ambush closets.** *(Doom's monster closets; Etrian
F.O.E. patrols.)* Today monsters are static furniture. Add: spawners that emit on a
timer/trigger, patrols that walk a path, and closets that disgorge enemies behind
you on `on-enter`. · *Hook:* `spawner` doodad + a patrol path (a list of macro
cells) + encounter events; counts/respawn gated by `bit`s. · *Editor:* a spawner
doodad, a path tool (reuse the line tool), a "trigger on" picker. · *Why:* converts
a static map into a *pressure field* the player reads and routes around — the
difference between a diorama and a dungeon.

**15. Mimics & trapped containers.** *(D&D staple.)* A chest/doodad that is secretly
a monster or a trap; looting has risk. · *Hook:* a `container` doodad with a hidden
`isMimic`/`trapped` payload resolved on `on-interact`. · *Editor:* container doodad
with a content/danger field. · *Why:* makes the simple act of *taking treasure* a
decision, which is exactly the tension you want when greed can kill a no-re-entry
run.

**16. Boss lock-in arenas → the clear condition.** *(Genre-wide.)* Enter a macro
region, the doors seal (idea 6), the boss spawns, and **defeating it is what
"clears" the dungeon** — flipping the terminal state that launches the story
reward. · *Hook:* a region with `lockOnEnter` + an `on-defeat <boss>` rule that
sets the dungeon's `cleared` terminal bit and triggers the reward `.story`. · *Editor:*
a "boss arena" region mode + a reward-story picker on the dungeon. · *Why:* this is
the **spine of the run**: the win state, made of primitives, testable as a VM
terminal (see regression safety in the level-design doc).

---

## E. Secrets, rewards & topology

**17. Optional vaults gated by keys found deeper.** *(M&M, Souls.)* A visible,
locked treasure you pass early whose key is *deeper in*, so greed pulls you forward
and rewards a return loop. · *Hook:* a `key` door (already built) + a key item
placed past it; pure level topology. · *Editor:* nothing new — it's a *design
pattern* over existing doors/items. · *Why:* the canonical risk/reward branch; the
no-re-entry rule sharpens it — the key only pays off if you can still reach the
vault, so it teaches route planning.

**18. Intra-dungeon shortcuts & landmarks.** *(Souls-like loop topology.)* Even with
no *dungeon* re-entry, opening a portcullis or kicking down a ladder that loops the
deep area back toward the entrance creates relief, mastery, and mental mapping —
and landmarks (a statue, a broken bridge) give the no-automap player anchors. ·
*Hook:* one-way doors (idea 6) + distinctive doodads as landmarks; portals for the
loop. · *Editor:* a "landmark" tag on doodads (drawn boldly). · *Why:* legibility
and pacing; the single biggest quality-of-life lever for a first-person grid map.

**19. Lore objects & environmental storytelling.** *(Everywhere.)* Murals,
inscriptions, corpses, readables that deliver story *in situ* — and hand off to the
`.story` graph (`documents/story.vm.md`) for richer beats. · *Hook:* a `lore`
doodad whose `on-interact` runs a `.story` id or shows a text op. · *Editor:* a lore
doodad with a story/text picker. · *Why:* the dungeon teaches its own fiction
without cutscenes; it's the connective tissue to the story engine.

---

## F. Meta / mapping

**20. Fog-of-war automap as an earned tool.** *(Etrian Odyssey's cartography.)*
The map is revealed as you walk (or hidden entirely until you find a "map" item),
and disruption mechanics (spinners, teleports, darkness) attack it. · *Hook:* a
per-macro `revealed` bit array (SPINE overlay), set on `on-enter`; an optional
`hasMap` gate. · *Editor:* nothing to author — it's a runtime overlay; optionally a
"starts mapped" dungeon flag. · *Why:* closes the loop with ideas 1–2 and 10 —
mapping is the meta-resource the disorientation mechanics spend, and earning it is
a reward in itself.

---

## Synergies & what to build first

These compound: spinner + teleport + darkness + fog-of-war is the *legibility*
system; plates + logic + one-way doors + sequence gates is the *puzzle* system;
floor hazards + light + camps + traps is the *attrition* system; spawners + mimics +
boss lock-in is the *encounter* system. A dungeon is a mix chosen from these four
dials.

For **Act I's first dungeon** (teaching, low stakes), the minimum viable set —
each a clean single lesson — is: **6** (one-way door = commitment), **5** (one
lever→one door), **9** (one damaging tile), **13** (one telegraphed trap), **17**
(one key→one vault), and **16** (a small boss lock-in → clear → first story beat).
Everything else is Act II+ vocabulary. The level-design doc sequences these into a
teaching curve; this doc is the menu they're drawn from.

> Status: idea catalog, not a spec. Each "hook" is a *proposed* extension to the
> model/DVM; pin exact fields/ops when implementing, and keep this list in lockstep
> with `documents/design.dvm.md` as ideas graduate into the format.
