# DEV-1307 与 continue.2 差异对比（字节码实证）

> 2026-10-14，双 jar 字节码级对比（javap/常量池/opcode），全部结论附证据。
> 样本：MTR-ANTE-1.1.1-DEV-1307+1.20.1.jar（作者侧）vs MTR-ANTE-1.1.1-continue.2+1.20.1.jar（社区续更侧）。

## 结论速览

**两条线已实质分叉，各修各的：作者修了 containsYSection 的比较符方向但未改 OR 形态；
我们修了 AND 重叠判定 + 曲率缓存 + 反序列化失效。DEV-1307 无曲率缓存（慢但正确）。**

## 逐项对照

### 1. EyeCandy 存在性（中断子代理的疑问）

DEV-1307 含 18 个 EyeCandy 类条目（fabric/ 前缀下 block/render/data 全套）——
**EyeCandy 功能在 DEV jar 中存在**，此前"缺失"是误判。

### 2. 滚转 getRollAngle（我们的 continue.1 修复）

| 特征 | DEV-1307 | continue.2 |
|---|---|---|
| `rollAngleMap` 字段 | ✓ 有 | ✓ 有 |
| `TreeMap` 隐式 0° 端点（修钳制 bug） | ✗ 无 | ✓ 有 |
| `SortedRollKeys` 二分缓存 | ✗ 无 | ✓ 有 |

DEV-1307 的 RailMixin（28756B）仍是原版逻辑：**中间节点→整轨误转的 bug 仍在**，
且每调用排序无缓存（正确但慢）。

### 3. containsYSection 光照区间（关键差异）

**DEV-1307 字节码**（javap 反汇编）：
```
(yMin<<4) dcmpg boundingBox.minY → iflt→true
(yMax<<4) dcmpl boundingBox.maxY → ifle→false
```
= `(yMin<<4) > minY || (yMax<<4) < maxY` —— **OR 形态保留**（与 c9765ea 原版同向），
作者只是调过比较符方向，"同层不重烘焙/无关层重建"的根本问题未解。

**continue.2**：`(yMax+1)<<4 > minY && yMin<<4 < maxY` 标准 AND 重叠判定。

### 4. Graal JS 沙箱（路线分叉）

| | DEV-1307 | continue.2 |
|---|---|---|
| 方案 | `IOAccess.allowIO` 直开（无沙箱） | `RootedScriptFileSystem` 根目录监狱 |
| 安全性 | JS 脚本可读写任意文件 | 相对路径锚定 ./ante/script_data/，拒绝越界+符号链接逃逸 |
| 说明 | 两者都能跑（绕开了 Graal 24.x 的 API 断裂） | continue 侧安全性更高 |

### 5. 缓存失效（我们的 continue.2 独有）

DEV-1307 无曲率缓存 → 无失效问题（也无缓存收益）。continue.2 的
messagepack/packet 两路径失效已闭环。

## 基线建议

**维持 continue.2 基线，不 rebase**：

1. 我们的 4 项修复中 3 项（滚转钳制/AND 光照/缓存失效）在 DEV-1307 里**不存在或未彻底修**；
2. Graal 沙箱是路线分叉而非谁对谁错——continue 侧安全性是社区续更的价值主张；
3. DEV-1307 的新功能（EyeCandy 等微调）可后续按需 cherry-pick；
4. 用户提供的 4 个 DEV jar（1.18.2~1.20.1）说明作者在多版本维护——continue 分支
   目前只需 1.20.1，暂无同步压力。

**行动项**：continue 分支继续独立演进；若社区反馈需要 DEV-1307 的某个新特性，
再按类级 cherry-pick（forgix 布局下 fabric/forge 前缀类是重定位产物，
不可直接拷贝，需从对应源码提交取）。
