# 更新日志（社区续更版）

本项目是 [aphrodite281/MTR-ANTE](https://github.com/aphrodite281/mtr-ante) 的社区续更分支。
原作者已停止维护，最后一个稳定版为 1.1.0（另有 1.1.1-preview 开发版）。
续更版基于 `alpha` 分支（1.1.1-preview-4, commit `c9765ea`）继续开发。

## 多版本矩阵（2026-10-14）

- **新增 MC 1.18.2 / 1.19.2 / 1.19.4 构建**（Fabric+Forge forgix 合并包），
  与 1.20.1 同修复集、同构建链（作者 GitHub Pages 三版本 MTR 依赖均可用）。
- 与作者 DEV-1307 的基线差异分析见 [docs/dev1307-comparison.md](docs/dev1307-comparison.md)；
  dev 环境 per-version mappings 限制见 [docs/multiversion-verification.md](docs/multiversion-verification.md)。
- 发布渠道：Modrinth（审核中）+ [GitHub Releases](https://github.com/szkele1145/mtr-ante-continue/releases)
  （v1.1.1-continue.2 挂四版本全矩阵 jar）。

## 1.1.1-continue.7

### 新增

- **★ 脚本执行预算自适应：区分「慢脚本」与「死循环」**。
  原先看门狗是**固定 10 秒**墙钟超时 ⇒ 一个合法但重的脚本（例如首次构建几何体
  要 12 秒）会和死循环一样被砍掉，且日志里两者长得一模一样，作者无法判断
  到底是自己写慢了还是写错了。

  判据：**只有已经成功完成过的脚本，才允许超过基础预算。**

  | 情形 | 预算 | 理由 |
  |---|---|---|
  | 从未成功完成（`successfulRuns < 2`） | 恒定 **10 s** | 死循环第一次就爆，不会被「自适应」放生 |
  | 已有成功历史 | `clamp(4 × 历史最长成功耗时, 10 s, 60 s)` | 慢脚本按自己的历史水平拿到余量 |

  只有「成功完成」的那次计入历史：失败/被中断的耗时若也计入，死循环会把
  自己的超时时长当成「历史最长耗时」，不断抬高自己的预算 —— **自我放生**。

  乘 4 而非 2：render 回调耗时抖动大（GC、区块加载、首次 JIT），2 倍容易反复误杀。
  上限 60 s：再长的单次调用是设计问题，且脚本线程是**单线程**（一个脚本卡住会拖住全部）。

  日志现在一眼能分辨：

  ```
  # 慢脚本
  ANTE script is slow: exceeded its adaptive budget of 20000 ms
    (base 10000 ms, 5 successful runs, longest successful run 5000 ms)

  # 疑似死循环
  ANTE script timed out after 10000 ms with no successful run on record
    (0 completed run(s) so far) - likely an infinite loop
  ```

- 新增 `ScriptBudgetPolicy`（`scripting` 包）：预算算法单独成类，
  **纯函数、零依赖，不引用 MC / GraalVM**。之所以抽出来是因为
  `ScriptHolderBase` 依赖 MC 与 GraalVM、离屏无法加载，而「预算怎么算」
  恰恰最需要回归；抽出来后 `tools/VerifyScriptTimeout.java` 直接调用
  **生产代码**，不复制参考实现，避免测试与实现悄悄分叉。

### 修复

- **`VerifyScriptTimeout` 的偶发假失败**（continue.6 起就存在）：
  事实 2/3/4 原先靠 `started2.countDown()` + 固定 `Thread.sleep(500)`
  来「等脚本跑起来」。机器负载高时（gradle daemon + 后台构建）victim 线程
  可能还没进入 polyglot 执行体，`interrupt` 就找不到可中断的目标，
  或 1 秒等待窗口在排队中耗尽 ⇒ 5 条断言随机失败（实测遇到过一次）。
  改为**由 JS 自己回报**：脚本进入循环前调用宿主回调 `__enterLoop()`，
  主线程 `await` 该 latch 再 interrupt —— 何时可中断由事实决定，
  不再由「猜一个睡够的时长」决定。

### 验证

- `verifyScriptTimeout` **PASS=26 FAIL=0**（continue.6 = 14，+12），连跑 4 次稳定。
- 新增断言涵盖：无成功历史恒基础预算（0 次 / 1 次 / 无耗时样本 / 负数样本）、
  4 倍放大、下限抬升、上限夹取，以及两条不变式（预算恒在 [10 s, 60 s]、
  耗时样本越大预算不减）。

## 1.1.1-continue.6

### 修复

- **★ 滚转排序缓存的并发可见性与数组配对缺陷**（`RailMixin`）：
  旧实现把滚转排序缓存拆成**两个独立字段**
  （`nteSortedRollKeys` / `nteSortedRollValues`），存在两个真实问题：
  1. **读写顺序相反** —— 写端 `nteRebuildRollCache()` 先赋 `values` 再赋 `keys`，
     读端 `RailExtraSupplier.getRollAngle()` 先取 `keys` 再取 `values`。
     ⇒ 渲染线程可能观察到「**新 keys + 旧 values**」，两个数组长度不一致
     ⇒ `values[idx]` **越界（AIOOBE）**。
  2. **无 `volatile`** ⇒ 编辑线程的失效写入对渲染线程**不可见**
     （无 happens-before）⇒ 滚转数据可能不更新；
     且 `nte$getSortedRollKeys()` 在 `invalidate` 与 `rebuild` 之间**可能返回 null**
     ⇒ `getRollAngle` 里 `keys.length` **NPE**。
  修法：把两个数组放进**不可变 holder**（`NteRollCache`），
  用**单个 `volatile` 字段**发布 ——
  volatile 写 = happens-before ⇒ 可见性保证；
  单字段发布 ⇒ keys 与 values **必然来自同一次重建、长度必然一致**。
  失效点从「置两个字段 null」简化为「置一个字段 null」
  （`setRollAngleMap` / messagepack 反序列化 / 网络包反序列化 三处）。

### 验证

- 新增 `tools/VerifyRollCacheVisibility.java`（独立 Java 程序，不依赖 MC / GraalVM），
  并接入 gradle 任务 `verifyRollCacheVisibility`：**PASS=8 FAIL=0**。
  压力测试（写线程持续重建 + 读线程按生产代码顺序取用）证明：
  - 旧的双字段实现能观察到「长度不一致的数组对」（缺陷真实存在）；
  - 旧实现能复现 **AIOOBE**（`Index 2 out of bounds for length 2`，
    即「新 keys 长度 3 + 旧 values 长度 2」的真实崩溃场景）；
  - 新的 holder 实现**永远配对**、无越界；
  - holder 失效后能读到新数据（可见性语义正确）。

### 文档更正

- 更正此前记录中关于「滚转缓存按轨道实例整体重建、编辑单条轨道会连带失效相邻轨道」
  的**错误描述**：逐行核实 `RailMixin` 后确认，滚转缓存本来就是
  **`RailMixin` 的 per-实例字段**，惰性重建、**只在本轨道自己的
  `setRollAngleMap` 时失效** ⇒ 粒度已经是最细的，**不存在**跨轨道连带失效。
  （历史上真正的跨轨道污染是 continue.2 修的另一个问题：
  messagepack / 网络包反序列化路径**绕过**失效 ⇒ 沿用**上一条轨道**的缓存快照；
  两条路径当时都已补上失效。）

## 1.1.1-continue.5（2026-10-22）

### 优化

- **JS 脚本超时看门狗的线程爆炸修复**（`ScriptHolderBase.startWatchdog`）：
  原实现每次脚本调用都新建 **2 个** 一次性线程（watchdog + watcher），
  而 `tryCallRenderFunctionAsync` 是**每帧每脚本**调用一次 ——
  60 FPS 下每个脚本每秒创建 **120** 个线程对象（栈内存 + GC 压力），
  资源包脚本一多（多列车/多景物各一个 context）就会短时间堆积数千个线程，
  高负载下会拖垮服务端。
  现改为：单一静态 `ScheduledExecutorService`（1 个 daemon 线程），
  每次调用只 **schedule** 一个延迟 10s 的一次性任务（不新建线程）；
  任务触发时用 `task.isDone()` 判断脚本是否已提前完成 ——
  若是则直接返回（等价于原来的 `done` 标志 + watcher 线程），
  若否则执行 `context.interrupt(Duration)`。
  **超时语义完全不变**（10s 超时 / 1s 宽限 / `AtomicBoolean` 幂等）。

### 验证

- `tools/VerifyScriptTimeout.java` 扩展到 **PASS=14 FAIL=0**（原 9 项 + 新 5 项）：
  - 事实 6：提前完成的脚本不会被中断（`isDone` 短路生效），context 仍可用；
  - 事实 7：超时脚本被中断并退出，抛 `PolyglotException`；
  - 事实 8：**连续 1000 次调度后看门狗线程数仍为 1**（未线程爆炸）——本次优化的核心断言。

## 1.1.1-continue.4（2026-10-21）

### 修复

- **JS 脚本无执行超时**（审查项 #9）：资源包作者写一个 `while(true){}` 会永久占住
  `SCRIPT_THREAD`（单线程 executor），之后**所有**脚本（包括其它资源包的）全部排队卡死，
  游戏表现为「脚本功能全失」且不报错。
  修复用 GraalVM 24.x 官方 `Context.interrupt(Duration)`（**唯一公开的执行中断入口**，
  且**只能由 creator thread 调用** —— 本类满足：context 在主线程创建、执行在 `SCRIPT_THREAD`），
  10s 超时由 daemon 看门狗触发，1s 宽限后若脚本仍卡在 Java 宿主调用则放弃（记日志）。
  中断后脚本线程收到 `PolyglotException: Execution got interrupted.`，
  走既有 `catch (Exception)` 记 `failTime`/`failException` —— **错误处理语义不变**。
- **构建脚本改用本地 Gradle 8.11**：`gradlew` 下载 gradle 8.11 会被 TLS 中间盒破坏
  （`AEADBadTagException`）；`build.ps1` 改调
  `F:\vibe coding工程\sfx\gradle-8.11\bin\gradle.bat`，参数化 `[mcVersion] [gradleTask]`。

### 验证

- 新增 `tools/VerifyScriptTimeout.java` + gradle 任务 `verifyScriptTimeout`，
  实测 **PASS=9 FAIL=0**（5 个事实：死循环确实停不下来 / `interrupt` 能中断 /
  抛 `PolyglotException` 可被既有 catch 接住 / 中断后 context 仍可用 /
  正常长脚本不被误杀）。
- 坑：`Context.eval` 返回 `org.graalvm.polyglot.Value` **不是** `Integer`；
  `org.graalvm.polyglot:js` 是 **POM-only**，真实语言实现是 `org.graalvm.js:js-language`。

## 1.1.1-continue.3（2026-10-21）

### 修复（代码审查报告 5 项）

- **#7 CONFIG_INFO 双重转义**（高）：原式 `toJson` → `replace("\"","\\\"")` → 塞进 JS 模板字符串，
  模板字符串再解释一次转义 —— 配置含 Windows 路径（`C:\mc\server`）/换行/制表符时
  `JSON.parse` 抛 `Bad escaped character`，**整个资源包 `load()` 失败**。
  改为 JSON 文本经 `globalBindings.putMember` 传入（**只编码一次**），
  JS 侧 `JSON.parse(CONFIG_INFO_JSON)` 出对象；`inject(String,String)` 一并改走 bindings。
- **#8 `boundingBox` 跨线程无可见性**（中）：后台线程写、渲染线程读，
  普通字段无 happens-before ⇒ 可能读到半更新 AABB。改 `volatile`（AABB 不可变，整体换引用）。
- **#10 `finishCallback` 不执行**（中）：异常时跳过 ⇒ `scriptCtx.created` 永 false
  ⇒ 每帧走 create 分支无限重试；`renderFunctionFinished()` 永不调用。移入 `finally`。
- **#11 死代码含全沙箱配置**（中）：`if (false) { ... }` 约 120 行，内含
  `allowHostClassLookup`(全放行)/`allowIO(ALL)`/`allowNativeAccess(true)`。整块删除。
- **#13 `RootedScriptFileSystem` root 未解引用符号链接**（低）：root 含符号链接时
  合法路径被误判越界。root 也做 `toRealPath()`。
- **#14 `resetRunner` 不等旧线程**（低）：`shutdownNow()` 后立刻换新 executor，
  旧线程可能仍持有 Context，与 `close()` 竞态。`awaitTermination(1s)`。

## 1.1.1-continue.2（热修）

- **滚转缓存在反序列化路径不失效**：`RailMixin` 的 messagepack（存档载入）与
  packet（网络同步）两条路径直接给 `rollAngleMap` 字段赋值，绕过
  `setRollAngleMap` 的缓存失效逻辑——新载入/同步的轨道会继续返回
  **上一条轨道**的滚转缓存快照（表现为存档载入后滚转数据错误，
  手动编辑该轨道才恢复）。两处赋值后现已同步清空排序缓存。

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
