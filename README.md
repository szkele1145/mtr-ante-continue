# MTR-ANTE Continued（社区续更版）

> **续更说明**：原作者 [Aphrodite281](https://github.com/aphrodite281) 已停止维护，
> 本仓库是其社区续更分支，基于 `alpha` 分支（1.1.1-preview-4）继续开发。
> 原项目：[aphrodite281/mtr-ante](https://github.com/aphrodite281/mtr-ante)（MIT）。
> 修复与优化内容见 [CHANGELOG.md](CHANGELOG.md)。

Aphrodite's Nemo's Transit Expansion (MTR-ANTE) 是一个基于 Minecraft Transit Railway Mod
的实验性功能扩展：连续曲线轨道、滚转（超高）、JS 脚本、复合轨道建造器。
本续更版支持 MTR 3.1.12 ~ 3.2.2（不支持 MTR 4.0.0）。

## 状态

**1.1.1-continue.5** — 四版本矩阵（1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 × Fabric+Forge 合并包），
已发布 [GitHub Releases](https://github.com/szkele1145/mtr-ante-continue/releases)
与 Modrinth（审核中）。

### 社区续更修复（相对作者最后的 alpha 基点）

| 修复 | 说明 |
|---|---|
| 滚转钳制 bug | 中部节点导致整轨误转、端点衔接断裂 → 隐式 0° 端点 + 二分缓存 |
| containsYSection 光照区间 | OR 形态（同层不重烘焙/无关层重建风暴）→ AND 重叠判定 |
| Graal 24.x 沙箱 | 内部 API 断裂（作者 1.1.1 搁浅根因）→ RootedScriptFileSystem 根目录监狱 |
| 滚转缓存失效（continue.2） | messagepack/packet 反序列化路径直接赋值绕过失效 → 双路径补失效 |
| 代码审查 5 项（continue.3） | #7 CONFIG_INFO 双重转义 / #8 boundingBox volatile / #10 finishCallback finally / #11 删死代码 / #13 root toRealPath / #14 resetRunner awaitTermination |
| JS 脚本执行超时（continue.4） | `while(true){}` 永久占住脚本线程 → 10s 看门狗 + GraalVM `Context.interrupt(Duration)` |
| 看门狗线程爆炸（continue.5） | 每帧每脚本创建 120 线程/秒 → 静态 `ScheduledExecutorService` 恒 1 线程 |
| 滚转缓存并发缺陷（continue.6） | 双字段读写顺序相反 + 无 volatile ⇒ AIOOBE 越界 / NPE → 不可变 holder + 单 `volatile` 字段原子发布 |

### 与作者 DEV-1307 的关系

作者在 GitHub 侧发布了 DEV-1307（四版本）；字节码对比显示其滚转钳制 bug 仍在、
containsYSection 仅改比较符未改 OR 形态、JS 沙箱走直开无防护路线。
完整分析见 [docs/dev1307-comparison.md](docs/dev1307-comparison.md)。
本续更版维持独立基线（3/4 修复 DEV 未修或未彻底）。

## 支持

- 原版文档：[aphrodite281.github.io/mtr-ante](https://aphrodite281.github.io/mtr-ante/#/)
- 问题反馈：本仓库 Issues

## 下载

- [GitHub Releases](https://github.com/szkele1145/mtr-ante-continue/releases)（四版本 jar 全矩阵）
- [Modrinth — MTR-ANTE Continued](https://modrinth.com/mod/mtr-ante-continue)（审核中）
- 历史原版：[原版 Releases](https://github.com/aphrodite281/mtr-ante/releases)、[Modrinth](https://modrinth.com/mod/mtr-ante/)

## 构建

环境：JDK 17+（推荐 17/21），Git。

在执行 `build` 任务前须先执行 `setupLibrary`，用 `-PbuildVersion` 指定 MC 版本
（可选 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4 / 1.20.1）。

```
./gradlew setupLibrary build -PbuildVersion=1.20.1
```

产物在 `build/MTR-ANTE-<version>+<mcver>.jar`（Fabric/Forge forgix 合并 jar）。

### 国内网络说明

- 依赖仓库：腾讯云镜像（前置）→ BMCLAPI → 阿里云 → 官方源兜底
- Gradle 插件：阿里云 gradle-plugin 镜像前置
- Gradle 发行版：腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`）
- 各版本 MTR 依赖从作者 GitHub Pages 自动下载（1.18.2~1.20.1 均可用）

> 工具链：Architectury Loom 1.10.431 + Gradle 8.11（1.9 系与 8.11 的仓库检查互锁
> 是作者停更前排障的死结）。dev 环境 `:fabric:runServer` 需 per-version mappings
> zip（作者仅提供 1.19.4）——release 构建（forgix）不受影响，详见
> [docs/multiversion-verification.md](docs/multiversion-verification.md)。

## 许可

MIT License（继承自 Zbx1425 的 [mtr-steamloco](https://github.com/zbx1425/mtr-steamloco)
与 Aphrodite281 的 mtr-ante），完整文本见 [LICENSE](LICENSE)。
