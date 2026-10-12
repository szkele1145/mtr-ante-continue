# MTR-ANTE（社区续更版）

> **续更说明**：原作者 [Aphrodite281](https://github.com/aphrodite281) 已停止维护，
> 本仓库是其社区续更分支，基于 `alpha` 分支（1.1.1-preview-4）继续开发。
> 原项目：[aphrodite281/mtr-ante](https://github.com/aphrodite281/mtr-ante)（MIT）。
> 修复与优化内容见 [CHANGELOG.md](CHANGELOG.md)。

Aphrodite's Nemo's Transit Expansion (MTR-ANTE) 是一个基于 Minecraft Transit Railway Mod
的实验性功能扩展，目前支持 MTR 3.1.12 ~ 3.2.2（不支持 MTR 4.0.0）。

## 支持

- 原版文档：[aphrodite281.github.io/mtr-ante](https://aphrodite281.github.io/mtr-ante/#/)
- 问题反馈：本仓库 Issues

## 下载

续更版发布后在此处与 Modrinth 提供；历史版本见
[原版 Releases](https://github.com/aphrodite281/mtr-ante/releases)、[Modrinth](https://modrinth.com/mod/mtr-ante/)。

## 构建

环境：JDK 17+（推荐 17/21），Git。

在执行 `build` 任务前须先执行 `setupLibrary`，用 `-PbuildVersion` 指定 MC 版本
（可选 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4 / 1.20.1）。
以 1.20.1 为例，第一次构建前执行：

```
./gradlew setupLibrary -PbuildVersion=1.20.1
```

每次构建时执行：

```
./gradlew build -PbuildVersion=1.20.1
```

产物在 `build/MTR-ANTE-<version>+<mcver>.jar`（Fabric/Forge 合并 jar）。

### 国内网络说明

本续更版已内置国内镜像，无需额外配置即可构建：

- 依赖仓库：腾讯云镜像（前置）→ BMCLAPI → 阿里云 → 官方源兜底；
- Gradle 插件：阿里云 gradle-plugin 镜像前置；
- Gradle 发行版：直接从腾讯云镜像下载（`gradle/wrapper/gradle-wrapper.properties`）；
- 如需走本地代理，在 `gradle.properties` 中按实际端口修改代理段。

> 工具链说明：Architectury Loom 已升级至 1.10.431（原 1.9.424 与 Gradle 8.11 的
> 仓库不可变检查不兼容，且 1.9 要求 Gradle 8.11+，形成死结——作者停更前
> 最后的提交正是在排障此问题）。Gradle wrapper 维持 8.11。
> 路径含空格/中文时 Loom 可能工作异常，建议在无空格路径下构建
> （Windows 可用 `New-Item -ItemType Junction` 建立无空格入口）。

## 许可

MIT License（继承自 Zbx1425 的 [mtr-steamloco](https://github.com/zbx1425/mtr-steamloco)
与 Aphrodite281 的 mtr-ante），完整文本见 [LICENSE](LICENSE)。
