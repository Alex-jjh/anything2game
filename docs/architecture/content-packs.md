# Content packs (ADR-017)

**Scope**: the one file format a course travels in (bundled preset, server download, import), and how each component uses it.

## 1. Format — `a2g.pack/v1`, file `<packageId>.a2gpack` (zip)

| Entry | Content | Rule |
|---|---|---|
| `manifest.json` | `schema`, `packageId`, `contentVersion`, `packageSchema`, `title`, `language`, `origin` (`preset` / `server` / `import`), `frames`, `entries[]` (`path`, `sha256`, `bytes`) | must be the **first** entry (cheap listing reads only this) |
| `package.json` | `LearningPackage` (`a2g.package/v1`) | passes `PackageValidator` |
| `tutor.json` (optional) | `TutorScript`: `{packageId, contentVersion, documentVersion, blueprint}`, the pre-generated diagnostic + explanation for this course; concepts may carry a `facet` (`background` / `method` / `result` / `limitation`) | `TutorRules.validate` against the package's sources; ids and versions equal the package's |
| `layout.json` (optional, presets only) | `PresetSourceLayout` (`a2g.sourcelayout/v1`): `packageId`, `documentId`, `documentVersion`, `pdfSha256`, `pdfBytes`, `pageCount`, `pdfSource`, `unaligned`, `layout` (`DocumentLayout`) — page geometry measured offline on one exact PDF that is **not** in the pack (ADR-019) | ids and versions equal the package's; every source chunk is laid out or named in `unaligned`; pages within `pageCount`; `pdfSha256` is 64 lowercase hex |
| `frames/<frameId>.json` | `FrameOverlay` per generated frame (classic needs none) | passes `FrameValidator` against the package |

The reader (`PackCodec.read`) accepts a pack only as a whole. It rejects a pack if: an entry is unknown or path-like, an entry is duplicated, there are too many entries, an entry or the whole pack exceeds the size limits (8 MB / 32 MB), the manifest is not first, a checksum or size does not match, the manifest and the contents disagree, or the package or a frame fails validation. Writing is deterministic: the same content always gives the same bytes.

## 2. Who uses it

```
samples/demo/*.json, contract/examples/*.json   (human-editable source of truth)
        │  PackTool (Gradle task :android:app:packPresets, every build)
        ▼
assets/packs/*.a2gpack ──► AssetPackSource ─┐
                                            ├─► ContentLibrary ──► PackageRepository / FrameRepository ──► engine
filesDir/packs/*.a2gpack ► DirectoryPackSource┘        (newer contentVersion wins; tie → installed)
        ▲  install(stream): validate fully, then atomic rename
        │
server: PackExporter(store) → PackCodec  (HTTP endpoint: later, security-reviewed)
imports: share sheet / file picker → install()  (later)
```

## 3. Adding a preset course

1. Put `package.<name>.json` and its `frame.<name>.<frameId>.json` files in `samples/demo/`.
2. Build. `:contract` tests validate the JSON, and `packPresets` packs it into the APK.
3. Optional: add `tutor.<name>.json` (same folder, same shape as `contract/examples/tutor.v1.example.json`) to give the course an offline explanation in the Learn tab. PackTool attaches it by `packageId`; an orphan or a second script for the same package fails the build.
4. To update a course, bump `contentVersion` in the package and its frames. An installed copy with a lower version will no longer override the preset.
5. Optional, PDF courses only: `layout.<name>.json`, written by the server's offline `PresetLayoutTool` from a local copy of the course's PDF (server/README.md, "Preset source layouts"). It lets a reader attach their own copy of that PDF and see "Show source" on the real page. Same pickup and orphan/duplicate rules as tutor files.

**Compatibility of `layout.json`.** Readers before ADR-019 reject any entry they do not know, so a pack with `layout.json` is refused whole by an older app. That is safe for bundled presets (an APK's reader and its packs ship together) and is why only PackTool presets carry it: the server export (`PackExporter`) and `installServerPackage` never add it. A pack without the entry is byte-identical to before. On the phone `ContentLibrary` validates the entry with the pack but does not keep it in its cache; `sourceLayoutFor` re-reads it when "Show source" needs it.
