# 更新日志（社区续更版）

本项目是 [aphrodite281/MTR-ANTE](https://github.com/aphrodite281/mtr-ante) 的社区续更分支。
原作者已停止维护，最后一个稳定版为 1.1.0（另有 1.1.1-preview 开发版）。
续更版基于 `alpha` 分支（1.1.1-preview-4, commit `c9765ea`）继续开发。

## 1.1.1-continue.1（未发布）

### 修复

- **滚转（超高）曲线在节点未覆盖区段的表现**：
  旧逻辑将"最近节点"的角度钳制到整个未覆盖区段——在轨道中部放置单个滚转节点时，
  整条轨道会一起滚转，且轨道端面带倾角，与相邻轨道衔接时出现明显断裂。
  现改为：节点之间平滑过渡，两端隐式归零（端点显式放置的节点仍优先，
  跨轨连续滚转不受影响）。列车滚转、轨道面片渲染、垂向补偿共用此函数，一并修复。
- **方块光照更新标记逻辑反転**（`RailChunkBase.containsYSection`）：
  原条件为"更新区段在 chunk Y 范围外"才返回真，完全颠倒——同层轨道的
  光照变化永不重烘焙（隧道里灯光不更新），而无关层面的更新反而触发重建，
  大型存档（多层高架/隧道）下形成持续重建风暴。现改为标准重叠判定。
- **JS 脚本沙箱文件系统**（`ScriptHolderBase`）：GraalVM 24.x 起
  `com.oracle.truffle.polyglot.FileSystems` 不再是公共 API（作者在
  22.3.0/23.x/24.2.x 间反复尝试均无法编译，1.1.1 因此搁浅）。
  续更版以纯公开 API 实现语义一致的替代：`RootedScriptFileSystem`
  （以 `./ante/script_data/` 为根的沙箱：相对路径锚定根目录、
  拒绝越界访问与符号链接逃逸），JS 列车/景物脚本功能恢复可用。

### 优化（保守）

- `RailExtraSupplier.getRollAngle`：旧实现每次调用都将整个滚转 Map 装箱、排序、线性扫描；
  该函数在多车多线存档下每帧调用上千次（每节车厢 × 每条轨 × 渲染与乘坐两路）。
  现改为惰性排序缓存 + 二分查找，查询路径零分配，显著降低 GC 压力。
- `RailRenderDispatcher`：
  - 轨道 chunk 排序结果缓存——仅在轨道结构变化或相机跨 chunk 时重新排序，
    消除大型存档下每帧 `O(n log n)` 排序造成的帧率毛刺；
  - 每帧增删差集运算复用容器，消除每帧 `HashSet` 分配；
  - 渲染距离判断改为逐 chunk 实时计算，修复缓存排序后
    相机小幅移动可能在距离边界产生错误剔除的问题。

### 构建

- 工具链现代化：Architectury Loom 1.9.424 → **1.10.431**（1.9 与 Gradle 8.11 的
  仓库不可变检查互不兼容，且 1.9 强制要求 8.11+，形成作者停更前的死结）；
  Gradle wrapper 维持 8.11 并由腾讯镜像分发；
  修复 fabric/forge 两处 `shadowJar` 配置期强解析依赖导致的
  "Cannot mutate content repository descriptor" 构建失败（改为惰性求值）。
- 新增国内 Maven 镜像（腾讯云 / 阿里云 / BMCLAPI），
  `settings.gradle` / `build.gradle` 已配置，并支持本地代理穿透，
  解决 `libraries.minecraft.net`、`services.gradle.org` 等官方源在部分网络下无法访问的问题。
- 移除已失效的 `nexus.velocitypowered.com` 仓库。
- 版本号：`1.1.1-continue.1`。

---

## 原版历史

原版更新日志见 [Releases](https://github.com/aphrodite281/mtr-ante/releases)。
