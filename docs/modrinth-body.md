# Modrinth 页面文案（发布用草稿）

## 标题
MTR-ANTE Continued

## Slug
mtr-ante-continue

## 简介（一句话）
Community continuation of ANTE — experimental extensions for MTR 3.2.x: continuous curves, cant/rolling, JS scripting, optimized rail rendering. Now on 4 MC versions.

## 正文

> **Continued** — The original author has stopped maintaining [MTR-ANTE](https://modrinth.com/mod/mtr-ante).
> This is a community continuation based on the last `alpha` (1.1.1-preview-4) sources, under the same MIT license.

Aphrodite's Nemo's Transit Expansion (ANTE) adds experimental features to the
[Minecraft Transit Railway](https://modrinth.com/mod/minecraft-transit-railway) mod (3.1.12 ~ 3.2.2, not 4.x):

- Continuous curved rails (replaces vanilla segmented rails) & better path-finding
- **Cant / superelevation (rolling)** for trains and track rendering
- In-game JavaScript scripting for trains & eye-candy, with extended JS environments
- Compound rail creator, more track speed limits, rail model customization
- Optimized instanced rail rendering (sowcer batch renderer)

### What this continuation fixes / improves

**Fixed**
1. Rolling (cant) interpolation: nodes outside covered segments are no longer clamped
   across the whole rail — rolling now transitions smoothly between nodes and eases to 0°
   at rail ends, so rails connect naturally at both ends (previously a single mid-rail node
   rolled the entire rail and broke joints).
2. Block-light updates now correctly mark only overlapping rail chunks for re-bake
   (the Y-range test was inverted: same-level light changes never re-baked, while
   unrelated updates triggered rebuild storms on large worlds).
3. **JS script sandbox**: GraalVM 24.x removed the internal API the original used
   (the reason 1.1.1 never shipped). Reimplemented with a public-API root-directory
   sandbox — JS scripts are anchored to `./ante/script_data/`, no escapes.
4. **Roll-cache invalidation** (1.1.1-continue.2 hotfix): freshly loaded or synced
   rails could keep serving the previous rail's cached roll data until edited.

**Optimized (conservative — no visual/behavioral changes)**
- Rolling angle queries: sorted-key caching + binary search
- Rail chunk sort cache: re-sorts only when structure changes or camera crosses a chunk
- Frame-diff scratch sets reused

### Versions & Compatibility
- **MC 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1** — Fabric+Forge merged jars (forgix)
- Requires MTR 3.1.12 ~ 3.2.2 (client & server); not compatible with MTR 4.x
- Fully compatible with YMTR (Yomi's MTR) — no ANTE FIX needed
- Works alongside JCM, YUM and other MTR 3.x addons

### Build from source
See the [GitHub repository](https://github.com/szkele1145/mtr-ante-continue) README
(mirrors + per-version MTR deps auto-download; dev-server smoke limited by
author-published mappings — release builds unaffected, details in the repo docs).
