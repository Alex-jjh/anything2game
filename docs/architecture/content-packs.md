# Content packs (ADR-017)

**Scope**: the one file format a course travels in (bundled preset, server download, import), and how each component uses it.

## 1. Format — `a2g.pack/v1`, file `<packageId>.a2gpack` (zip)

| Entry | Content | Rule |
|---|---|---|
| `manifest.json` | `schema`, `packageId`, `contentVersion`, `packageSchema`, `title`, `language`, `origin` (`preset` / `server` / `import`), `frames`, `entries[]` (`path`, `sha256`, `bytes`) | must be the **first** entry (cheap listing reads only this) |
| `package.json` | `LearningPackage` (`a2g.package/v1`) | passes `PackageValidator` |
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
3. To update a course, bump `contentVersion` in the package and its frames. An installed copy with a lower version will no longer override the preset.
