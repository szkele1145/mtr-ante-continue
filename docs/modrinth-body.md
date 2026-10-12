# Modrinth 页面文案（发布用草稿）

## 标题
Aphrodite's Nemo's Transit Expansion (Continued)

## Slug
mtr-ante-continue

## 简介（一句话）
Community continuation of ANTE — experimental extensions for MTR 3.2.x: continuous curves, cant/rolling, JS scripting, optimized rail rendering.

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
- Rolling (cant) interpolation: nodes outside covered segments are no longer clamped
  across the whole rail — rolling now transitions smoothly between nodes and eases to 0°
  at rail ends, so rails connect naturally at both ends (previously a single mid-rail node
  rolled the entire rail and broke joints).
- Block-light updates now correctly mark only overlapping rail chunks for re-bake
  (the Y-range test was inverted: same-level light changes never re-baked, while
  unrelated updates triggered rebuild storms on large worlds).

**Optimized (conservative — no visual/behavioral changes)**
- Rolling angle queries: sorted-key caching + binary search (the old code boxed and
  sorted the whole map on every call — a measurable GC hotspot with many trains/lines)
- Rail chunk sort cache: re-sorts only when rail structure changes or the camera
  crosses a chunk boundary (previously every frame), removing frame spikes on large worlds
- Frame-diff scratch sets reused instead of per-frame HashSet allocations

### Compatibility
- Requires MTR 3.1.12 ~ 3.2.2 (client & server)
- Works alongside JCM, YUM and other MTR 3.x addons
- Not compatible with MTR 4.x

### Build from source
See the [GitHub repository](https://github.com/szkele1145/mtr-ante-continue) README
(mirrors + proxy support built in for CN networks).

## 标签
technology, transportation, fabric, forge, 1.20.1 (+其它按实际发布版本)

## 环境声明
client & server 均必需（required）
