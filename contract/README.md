# contract — the single client↔server agreement

**Scope**: the shapes of every request, response and learning package, plus the validator both sides run. Prose lives in [ARCHITECTURE §3](../docs/architecture/ARCHITECTURE.md#3-the-contract-v1); this folder is the source of truth.

- `src/main/kotlin/` — DTOs (`kotlinx.serialization`) and `PackageValidator` (schema, ids, quote ⊂ chunk, answer consistency).
- `examples/` — canonical JSON. Every file is parsed and validated by tests on **both** `contract` and `server`; `:android:app` loads the same files as its offline sample.
  - `package.v1.example.json` — one level, three mechanics (Swipe, Sort, Deduce). The source text is written by the team.

## Change rules

1. Additive change (new optional field, new mechanic type) → bump nothing, add an example.
2. Breaking change → new `schema` value (`a2g.package/v2`), keep v1 readable until both sides migrate.
3. A PR that changes a DTO must change an example and a test in the same commit.

Mechanic item shapes (discriminator `item.type`): `swipe`, `sort`, `deduce`; reserved: `order`, `match`.
