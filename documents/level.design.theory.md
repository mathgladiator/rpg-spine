# Level design theory — teaching dungeons that cascade

This is the bridge between the *format* (`documents/design.dvm.md`,
`DUNGEON_WALLS.md`) and the *menu of verbs* (`documents/future.dungeon.ideas.md`).
It answers: given the engine can express these things, **how do we author levels
that teach, escalate, and pay off** — and how do we make the VM robust enough that
building them never silently regresses.

It is written around the game's defining constraint, because that constraint
changes everything downstream:

> **A dungeon is a committed run.** You enter once. You cannot leave. You either
> die or you clear it. Clearing grants a unique reward: a story-mode sequence
> (`documents/story.vm.md`). There is no save-scum exit, no town-trip to heal, no
> re-entry to grind.

## 1. What kind of game this makes

The no-re-entry rule is not a restriction bolted onto a normal RPG — it *is* the
genre. It places the game next to the **roguelike descent**, the **Souls run
between bonfires**, and the **escape-room**: a bounded, self-contained gauntlet
where every resource you'll get is already inside, and the only currencies that
matter are *health, light, consumables, and information*. Three consequences fall
out immediately, and the rest of this document is their elaboration:

1. **The dungeon must be internally resourced and internally fair.** If the player
   can't leave to restock, the level must contain everything a careful player needs
   to survive it — and an unfair instadeath isn't "hard", it's a broken contract.
2. **Commitment must be telegraphed.** The point of no return is the *entrance*.
   The player deserves to know they're committing before they step in, and the
   first dungeon must *teach the rule itself* at low stakes.
3. **The reward is emotional, not material.** Because you can't return to use
   loot in old content, the payoff for clearing is **story** — narrative
   progression you earned by surviving. The dungeon is a *question*; the `.story`
   sequence is its *answer*.

## 2. First principles for a no-automap, first-person grid crawler

The player sees one corridor at a time. Their map is in their head (until they
earn one — `future.dungeon.ideas.md` #20). So **legibility is the first design
budget**, and it spends on Kevin Lynch's vocabulary of a navigable space — *paths,
edges, nodes, districts, landmarks*:

- **The macro cell is the player's unit of thought.** Movement is macro-quantized
  for a reason: people remember "third junction, turn left", not micro coordinates.
  Design rooms and decisions on the macro grid; let micro detail be texture.
- **Asymmetry is a landmark.** A perfectly symmetric maze is unmappable. Break
  symmetry deliberately: a statue here, a bloodstain there, a different wall
  material for the east wing. Landmarks (ideas #18–19) are not decoration — they
  are the player's coordinate system.
- **The cartography contract.** Every disorientation mechanic (spinner, teleport,
  darkness) is a *withdrawal* from the legibility budget; every landmark, distinct
  material, and revealed-map tile is a *deposit*. Stay solvent. A confused player
  in a no-re-entry dungeon isn't intrigued, they're trapped — and they'll feel the
  difference between *mystery* (deposits available, not yet spent) and *mystification*
  (overdrawn).

## 3. The teaching grammar

Use the four-beat loop that underlies the best tutorial-free design (Mario, Zelda,
Portal), expressed here in dungeon terms. For **each** mechanic you introduce:

1. **Introduce** it in a *safe, isolated* room where it can't hurt you and its
   cause/effect is unmistakable. (A lever; you pull it; a door three feet away
   opens. Nothing else in the room.)
2. **Practice** it with a trivial application and no time pressure. (Now the lever
   is across the room from the door; you must look.)
3. **Test** it under stakes — combine it with a hazard or a clock. (Pull the lever
   while a monster approaches; the door is your escape.)
4. **Twist / combine** it with a previously-taught mechanic to produce something
   neither taught alone. (Two levers, one opens the door and one springs a trap;
   now the *clue* from idea #8 matters.)

The grammar's golden rule: **introduce one verb at a time.** A room that teaches
two new things teaches neither. Sequence the verbs; let mastery compound.

## 4. Act I: the first dungeon as a place that happens to be a tutorial

The first dungeon must teach (a) the *rule of the game* — commitment, attrition,
no exit — and (b) a small starting vocabulary, while feeling like a *place*, not a
classroom. A concrete macro-grid blueprint, each zone a single lesson, drawn from
the Act-I minimum set in `future.dungeon.ideas.md`:

| Zone | Lesson (verb) | Mechanic | Beat |
|------|---------------|----------|------|
| **Threshold** | *You are committing.* | one-way door (#6) seals behind you | the door slams; a line of text names the stakes |
| **Antechamber** | movement & legibility | a landmark statue; a spinner is *foreshadowed* but inert | learn to read the macro grid |
| **Lever hall** | cause → effect | one lever → one door (#5), isolated | introduce → practice |
| **The wet floor** | attrition | one damaging tile (#9) you must cross or route around | first "resources are finite" lesson |
| **The trap corridor** | caution | one telegraphed, detectable trap (#13) | test: move carefully under threat |
| **The locked vault** | greed vs. safety | a key door (#17); key is just ahead, guarded | first optional risk/reward |
| **The arena** | the clear condition | a small boss lock-in (#16) | doors seal, boss falls, dungeon **clears** |
| **The reward** | the payoff | terminal `cleared` → launches the first `.story` | narrative answer to the run |

Notice the shape: stakes ramp monotonically, each room owns exactly one idea, the
*rule of the game* is taught first and at the lowest possible stakes, and the run
ends on its emotional high — the story reward. By the boss, the player has, without
a single tooltip, learned commitment, mapping, switches, hazards, traps, locks, and
the win/lose contract. Act II can now *assume* all of it.

## 5. Pacing & the attrition budget

Under no-re-entry, pacing *is* resource management. Author it explicitly:

- **The attrition curve.** Treat HP/light/consumables as a budget the dungeon
  spends down. Plot intended remaining-resources across the critical path; the boss
  should be reachable by a careful player with a *meaningful but survivable*
  deficit. Optional content (vaults, side rooms) trades extra resource cost for
  reward — the player chooses how deep into the budget to dig.
- **Tension/release rhythm.** Alternate pressure (combat, hazard, darkness) with
  relief (a safe junction, a camp #12, a story beat). Flat tension exhausts; flat
  calm bores. The camp points (idea #12) are the *deliberate* release valves — their
  placement defines the dungeon's breathing.
- **The "can I make it?" moment.** Every good committed run has a stretch where the
  player, low on resources, must decide whether to push or to spend their last
  relief. Design *toward* that moment; it's where the no-re-entry rule turns from a
  restriction into a thrill.

## 6. Topology: lock-and-key and loops

On the macro grid, structure the dungeon as a **lock-and-key graph** (the Zelda
dungeon discipline):

- A **critical path** of gates (doors, sealed regions, the boss arena) that must be
  opened in order, each key/lever a guaranteed pickup along the way.
- **Optional branches** (vaults #17, secrets #7) hanging off the path — pure
  risk/reward, never required, always telegraphed.
- **Loops, not lines.** A pure line is forgettable; a loop that lets the deep area
  shortcut back toward earlier ground (one-way doors #6, #18) builds mastery and a
  mental map even though you can't *leave* the dungeon. Forward-only does not mean
  branch-free.

The no-re-entry rule sharpens every lock: a key behind a one-way door is a key you
must *use before passing*, so the lock teaches planning, not just fetching.

## 7. The cascade: from Act I primitives to endgame dungeons

Later dungeons don't add *new categories* of thought so much as **recombine,
subvert, and layer** the Act-I verbs. The progression is a vocabulary expanding,
not a genre changing:

| | Act I (teach) | Act II (combine) | Endgame (subvert & layer) |
|--|--------------|------------------|---------------------------|
| **Doors** | one lever, one door | logic gates (#5), sequences (#8) | doors that lie (illusory #7), seal you in arenas |
| **Hazards** | one damaging tile | hazard + darkness (#10) + flow (#3) | a floor whose safe path *moves*; anti-magic (#11) |
| **Navigation** | a landmark | a spinner you can map around (#1) | teleport mazes (#2) that attack an *earned* map (#20) |
| **Encounters** | one telegraphed fight | patrols & spawners (#14) | mimic-dense vaults; multi-phase boss lock-ins |
| **Topology** | a single loop | optional vaults (#17) | multi-floor shafts (#4); dungeons that reconfigure mid-run |
| **Story** | one reward beat | lore objects (#19) seed the plot | choices *inside* the run that the `.story` reward pays off |

The endgame dungeon is the Act-I grammar spoken fluently and *against* itself: it
teaches the player a rule for hours, then breaks it once, deliberately, as a
climax — the illusory wall after a hundred honest ones, the spinner in the room you
thought was safe. That subversion only lands because Act I made the rule reflexive.
**Design the betrayal into the curriculum from the start.**

## 8. Robustness & regression safety (why you can implement the VM and sleep)

You asked to feel *protected against regressions* before committing to the DVM.
The design discipline above is what makes that achievable, because every concept in
it reduces to something **executable and assertable**. Build these guardrails
alongside the VM and a level becomes a thing you can *test like code*:

- **Round-trip property tests.** `compile(.dungeon) → .dvm → decode` must
  reconstruct the exact `cells`, `macroFill`, features, doors, and regions. The
  existing `DungeonModelTests` round-trips are the seed; extend them to the binary
  layer. A random-dungeon generator fuzzing encode/decode catches codec drift for
  free.
- **Golden-file snapshots.** Freeze the compiled bytes (and a human-readable op
  dump) of a handful of canonical dungeons. A diff in the golden file is a
  *deliberate* format change or a *regression* — never a silent one. (Same
  discipline as `BWA_FORMAT.md`'s checksummed chunks.)
- **The terminal states are first-class and testable.** A run has exactly three
  outcomes — `IN_PROGRESS`, `CLEARED`, `DIED`. Because they're explicit VM state
  (a `cleared` bit set by the boss rule, a `died` bit set by HP≤0), you can write a
  **headless level-simulation harness**: feed a scripted player path (a list of
  moves/interactions) into the interpreter and assert the terminal state and the
  resulting SPINE overlay. "Pulling lever A then fighting the boss clears the
  dungeon and sets `flag:act1_done`" becomes a unit test.
- **Reachability & solvability linting.** Static checks the editor (and CI) runs on
  every `.dungeon`: the boss/clear condition is reachable from the entrance through
  the lock-and-key graph; every required key precedes its door on the critical
  path; no soft-lock (a one-way door that strands a required key behind it); the
  attrition budget is non-negative along the critical path. The asset audit
  (`AssetAudit`) already proves the *file-reference* version of this; this is its
  *gameplay* twin.
- **Determinism.** The VM must be deterministic given (seed, input) — no
  `Math.random()` in the hot path (the project already bans it in some contexts).
  Determinism is what makes the simulation harness and golden files *possible*; it
  is the foundation, not a nicety.

The throughline: **every mechanic in `future.dungeon.ideas.md` is a VM op + a
SPINE bit + a test.** Design a level by composing ops; protect it by snapshotting
the compile and simulating the playthrough. The robustness isn't bolted on after —
it's the same discipline (explicit state, stable ids, round-trippable encodings)
that the rest of this codebase already lives by.

## 9. Anti-patterns (especially deadly under no re-entry)

- **Mystery-meat navigation** — disorientation with no landmarks to spend it
  against. Confusion, not challenge.
- **Untelegraphed commitment** — sealing the player in without warning. The rule
  must be *taught* (Act I, Threshold), then *trusted*.
- **Unfair instadeath** — a trap with no tell, in a game where death ends the run.
  Hazards must be *readable*; the punishment for misreading can be severe, the
  *absence of a tell* cannot.
- **Soft-locks** — a required resource stranded behind a one-way gate. The
  solvability lint exists to make this a build error, not a player tragedy.
- **The unearned reward** — a story payoff disproportionate to the run, or a run
  with no payoff. The dungeon and its `.story` are a question and its answer; a
  mismatch deflates both.

> Status: design theory, not a spec. It assumes the DVM and the story VM as
> designed in their docs. Keep in lockstep with `documents/design.dvm.md`,
> `documents/future.dungeon.ideas.md`, and `documents/story.vm.md`; as mechanics
> and lints graduate into code, point this doc at the tests that enforce them.
