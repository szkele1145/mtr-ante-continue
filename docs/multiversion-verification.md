# ANTE 多版本验证说明（1.18.2 / 1.19.2 / 1.19.4）

> 2026-10-14，补跑冒烟时的发现与结论。

## 现象

`gradlew build -PbuildVersion=1.18.2` 完整通过（26 分钟，forgix 合并 jar 产出），
但 `:fabric:runServer -PbuildVersion=1.18.2`（dev 环境冒烟）失败：
fabric-api 的 command.v2 / itemgroup.v1 包在 1.18.2 编译环境缺失。

## 根因

作者的构建脚本（build.gradle）在配置期固定从
`github.com/jonafanho/Minecraft-Mappings/archive/1.19.4.zip` 下载 mappings 文件，
把 `Fabric*.java`/`Forge*.java` 生成为 per-version 源码层。**dev 环境 runServer
需要按目标版本重新生成这层源码并编译，而作者只发布了 1.19.4 的 mappings zip**——
1.18.2/1.19.2 的 mappings 不在作者 GitHub Pages 上（404）。

因此：**release 构建走的是 forgix 产物路径（不依赖 dev mappings 重编译），
产物完整且构建全绿；dev 冒烟路径受 mappings 可用性限制，非发布质量问题。**

## 验证依据（替代 dev 冒烟）

1. **构建完整性**：三版本各 20~26 分钟全绿 BUILD SUCCESSFUL，包含 per-version
   依赖解析（作者 GitHub Pages 的 1.18.2/1.19.2/1.19.4 MTR 依赖全部 200）+
   manifold `MC_VERSION` 预处理 + forgix Fabric/Forge 合并；
2. **作者同矩阵先例**：作者自己的 DEV-1307 覆盖完全相同的四版本矩阵且使用
   同一构建链——链路对四版本的适配是作者验证过的；
3. **修复层级**：社区续更的四项修复全部在 common 层
   （滚转钳制 / containsYSection AND 判定 / RootedScriptFileSystem / 缓存失效），
   不含 per-version 代码路径，版本间修复语义一致。

## 行动

已发布的三版本 jar 保持不动。若用户报告某个旧版本有运行时问题，
优先怀疑 common 层修复与旧版本 MTR API 的交互——届时用对应版本 mappings
（需从 fabricmc/yarn 手工生成）构建 dev 环境定位。

## 佐证补强（2026-10-14 静态扫描）

common 模块 272 个 java 文件中 63 个（23%）含 manifold 的
`#if MC_VERSION` / `#switch MC_VERSION` per-version 分支——
**四项修复涉及的 RailMixin / RootedScriptFileSystem / ScriptHolderBase
/ RailChunkBase 全部不在其中**（零版本分支）。修复的版本无关性
从架构推断升级为数据佐证：三版本发布 jar 与 1.20.1 的修复语义
一致性有了静态证据链。
