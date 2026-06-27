# SAVE_FORMAT: the spine save bytecode

The save file **is** the SPINE document, encoded as a **mutation-command
bytecode**: a flat stream of op codes that, replayed against a freshly-allocated
`SPINE`, reconstructs the whole document. This is the file format Milestone 1 of
the README describes, specified concretely.

Two properties drive the design:

- **Field codes are globally unique** across the root document and every struct
  (the `.rpg` editor enforces this). A field code therefore names exactly one
  destination, independent of where it appears in the stream.
- **Self-describing values.** Every scalar op encodes its value *width*, so a
  loader that meets an unknown field code (a field removed in a patch, or a field
  a newer save added that this binary predates) can **skip** it by width instead
  of corrupting memory. This is the Thrift-style robustness the README wants from
  the field-code mechanism.

Everything multi-byte is **big-endian** (network order), so saves move between
Playdate, Linux, Windows, wasm and Android unchanged.

## Container

```
+--------+--------------------+------------------+--------+----------+
| magic  | format_version u16 | command stream … | OP_END | crc32 u32|
| "SPN1" |                    |                  | (0x00) |          |
+--------+--------------------+------------------+--------+----------+
```

- `magic` = the 4 bytes `0x53 0x50 0x4E 0x31` (`"SPN1"`).
- `format_version` = `1` for this revision. Bumped only on incompatible
  container changes (the op table below is meant to grow *additively*).
- `crc32` covers every byte from `magic` through `OP_END` inclusive. A loader
  recomputes it and rejects the blob (returns `NULL`) on mismatch — corruption
  fails loudly, never silently.

A loader that doesn't see the magic, or sees an unknown `format_version`, returns
`NULL`.

## Op codes

One byte each. Structural ops drive a tiny VM; scalar ops carry a value.

### Structural

| Op            | Byte | Operands                    | Effect                                                            |
|---------------|------|-----------------------------|------------------------------------------------------------------|
| `OP_END`      | 0x00 | —                           | end of stream                                                    |
| `OP_ENTER_ARRAY` | 0x01 | field_code u16, count u32 | allocate the array field `field_code` with `count` elements; set the cursor to element 0 |
| `OP_NEXT`     | 0x02 | —                           | advance the cursor to the next element of the current array       |
| `OP_EXIT_ARRAY` | 0x03 | —                         | clear the cursor (subsequent scalars target the root)            |

### Scalars

Each is `opcode, field_code u16, value`. The width is fixed by the opcode:

| Op          | Byte | Value bytes | C type     |
|-------------|------|-------------|------------|
| `OP_BOOL`   | 0x10 | 1           | `bool`     |
| `OP_INT8`   | 0x11 | 1           | `int8_t`   |
| `OP_UINT8`  | 0x12 | 1           | `uint8_t`  |
| `OP_INT16`  | 0x13 | 2           | `int16_t`  |
| `OP_UINT16` | 0x14 | 2           | `uint16_t` |
| `OP_INT32`  | 0x15 | 4           | `int32_t`  |
| `OP_UINT32` | 0x16 | 4           | `uint32_t` |
| `OP_INT64`  | 0x17 | 8           | `int64_t`  |
| `OP_UINT64` | 0x18 | 8           | `uint64_t` |
| `OP_BYTES`  | 0x19 | len u32 + N | blob/str   |

Field codes are `uint16` (`0..65534`; `0xFFFF` reserved). The demo schema tops
out at `1000`, well within range. (If a schema ever needs more, bump to a varint
field code under a new `format_version`; not needed now.)

## The reconstruction VM

State: a single **cursor** — `{ which array is active, element index, element
pointer }` — plus the root document pointer. No nesting yet (recursion is the
documented future extension: replace the single cursor with a stack).

Replay:

1. Read + verify the container header.
2. Loop reading ops until `OP_END`:
   - **scalar op** → read field_code + value, then *apply*: if `field_code` is a
     root field, write it into the document; if it is a struct-member field,
     write it into the **current cursor element**. Field codes are unique so the
     destination is unambiguous. An unknown code → skip the value by its width.
   - `OP_ENTER_ARRAY` → allocate that root array field to `count`, cursor → elem 0.
   - `OP_NEXT` → cursor → next element (loader knows the element type from which
     array is active, so it knows the stride).
   - `OP_EXIT_ARRAY` → cursor inactive.
3. Verify the trailing crc32.

A struct-member scalar with no active cursor, an `OP_NEXT` past `count`, or a
truncated read all mark the loader failed → it frees partial state and returns
`NULL`.

### Save (the inverse)

`spine_save` walks the document in a fixed, deterministic order and emits the
ops: root scalars first, then each array as `OP_ENTER_ARRAY` + per-element
scalars separated by `OP_NEXT` + `OP_EXIT_ARRAY`, then `OP_END`, then the crc32.
Deterministic order makes golden-file tests and round-trip diffs trivial.

## Public C API (generated prototypes in `spine.gen.h`)

```c
SPINE*   spine_load(const uint8_t* data, int size);  /* NULL on any error */
uint8_t* spine_save(const SPINE* doc, int* out_size);/* caller frees with free() */
void     spine_free(SPINE* doc);                     /* frees doc + its arrays */
```

## Split: generated vs. runtime

- **Runtime** (`spine_runtime.{h,c}`, hand-written, unit-tested, in
  `rpg-spine/runtime/`): a bounds-checked reader and a growable writer over a byte
  buffer, with big-endian `spine_read_op_code` / `spine_read_int8` /
  `spine_write_int8` / … primitives and a `spine_crc32`. Knows nothing about any
  schema. This is the auditable core.
- **Generated** (`spine.gen.{h,c}`): the `SPINE` struct + `FC_*` field codes +
  `EFF_*` ids + `effect_<name>` prototypes, and `spine_load`/`spine_save`/
  `spine_free` bodies built **only** from runtime primitives — the Thrift-style
  reader/writer, mechanically derived from the schema.

## Type → op mapping (codegen)

The `.rpg` type identifier picks the op + C type:

| schema type      | op          | C field type |
|------------------|-------------|--------------|
| `bool`           | `OP_BOOL`   | `bool`       |
| `byte` / `u8`    | `OP_UINT8`  | `uint8_t`    |
| `i8`             | `OP_INT8`   | `int8_t`     |
| `i16` / `short`  | `OP_INT16`  | `int16_t`    |
| `u16`            | `OP_UINT16` | `uint16_t`   |
| `int` / `i32`    | `OP_INT32`  | `int32_t`    |
| `u32`            | `OP_UINT32` | `uint32_t`   |
| `long` / `i64`   | `OP_INT64`  | `int64_t`    |
| `u64`            | `OP_UINT64` | `uint64_t`   |

A field whose type is a **struct name** with `[]` is an array; codegen emits the
typedef and the `OP_ENTER_ARRAY`/`OP_NEXT` walk. (Unknown/unsupported types are a
codegen error.)
