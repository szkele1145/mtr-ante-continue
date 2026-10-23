# Mixin 可见性语义：字节码实证（Mixin 0.8.5）

> 2026-10-23。本文更正了本仓库历史上一条**错误结论**。

## 结论（先给答案）

| 场景 | 真实行为 | 是否崩客户端 |
|---|---|---|
| `@Shadow` 方法声明为 `private`，目标为 `public` | **运行时静默把声明的可见性升级为 `public`** | **否** |
| `@Shadow` 方法声明为 `protected`/包私有，目标为 `public` | **什么都不做，也不报错** | **否** |
| `@Shadow` 字段可见性低于目标 | **根本不检查可见性** | **否** |
| `@Overwrite` 可见性低于目标，且 `conformOverwriteVisibility()` 为 false | 抛 `InvalidMixinException` | **是** |
| `@Shadow` 成员在目标类（含父类）中找不到 | 抛 `InvalidMixinException`（`@Shadow field/method ... was not located`） | **是** |
| `@Shadow` 字段的 `static` 修饰与目标不一致 | 抛 `InvalidMixinException`（`STATIC modifier ... does not match`） | **是** |

**一句话**：`@Shadow` 的可见性写窄了**不会崩**，Mixin 会自己迁就；真正会崩的是
「目标不存在」「static 不匹配」「`@Overwrite` 且配置不允许改写可见性」。

## 证据

反编译 `mixin-0.8.5.jar` 的
`org/spongepowered/asm/mixin/transformer/MixinPreProcessorStandard.class`
（`javap -c -p`），方法 `conformVisibility(MixinTargetContext, MixinMethodNode, SpecialMethod, MethodNode)`：

```
 0: aload 4                       // 形参 4 = 目标 MethodNode
 2: invokestatic  Bytecode.getVisibility  → var5 = targetVisibility
 7: aload_2                       // 形参 2 = mixin 自己的 MixinMethodNode
 8: invokestatic  Bytecode.getVisibility  → var6 = methodVisibility
13: aload 6
15: invokevirtual Visibility.ordinal
18: aload 5
20: invokevirtual Visibility.ordinal
23: if_icmplt 58                  // if (methodVis < targetVis) 跳到 58
...
57: return                        // 正常路径：不报错
58: ldc  "%s %s method %s in %s cannot reduce visibiliy of %s target method"
97: aload_3
98: getfield SpecialMethod.isOverwrite
101: ifeq 131                     // ★ 非 @Overwrite ⇒ 直接跳到 131，不抛
117: new InvalidMixinException    // 只有 isOverwrite 为真 且 配置禁止时才抛
130: athrow
131: aload 6
133: getstatic Visibility.PRIVATE
136: if_acmpne 176                // 声明不是 PRIVATE ⇒ 直接返回（连日志都没有）
165: MixinTargetContext.addUpgradedMethod(method)
170: Bytecode.setVisibility(method, targetVisibility)   // ★ 静默升级
176: return
```

配套事实：

- `Bytecode$Visibility` 的 ordinal 顺序 = **PRIVATE(0) < PROTECTED(1) < PACKAGE(2) < PUBLIC(3)**
  （`<clinit>` 里依次 `putstatic`，`iconst_0..3`）。
- `SpecialMethod` 的枚举常量与 `isOverwrite`：
  `MERGE=false`、`OVERWRITE=true`、`SHADOW=false`、`ACCESSOR=false`、`INVOKER=false`
  （`<clinit>` 里 `MERGE` 传 `iconst_0`，`OVERWRITE` 传 `iconst_1`）。
- `@Shadow` 方法走的是 `attachMethod(…)`，传给 `conformVisibility` 的是
  **`SpecialMethod.MERGE`**（`attachMethod` offset 75），所以 `isOverwrite == false`。

`MixinPreProcessorStandard.attachFields` 里对 `@Shadow` 字段只有这些致命检查：

- `@Shadow field %s was not located in the target class %s`
- `STATIC modifier of @Shadow field %s in %s does not match the target`
- 非 `@Unique` 且描述符不一致 ⇒ `The field %s in the target class has a conflicting signature`
- 非私有字段想改名 ⇒ `Non-private field cannot be aliased`

**没有任何一项比较可见性。**

## 被更正的历史结论

本仓库（以及 `MTR-交接文档.md` §10.1）曾断言：

> `@Shadow` 声明的可见性低于目标方法的可见性 ⇒ 运行时 `InvalidMixinException` ⇒ 崩客户端

**这是错的。** 推断来源是只看到那句报错文案，没有反编译条件分支。
该错误结论还导致 mtr-turbo 1.1.6 把 `@Shadow` 可见性对齐当成了「崩溃修复」——
那次改动本身无害（把 `protected` 改成与目标一致的 `public`），
但它**并不是** 1.1.6 真正解决问题的原因；1.1.6 的实际价值在于
新增 Forge 1.19.4 与完整版本矩阵。

## 对 ante 的审计结果

`auditMixinInjectors` 对 ante 的 35 个 mixin 文件跑了本套检查：

| 项 | 结果 |
|---|---|
| 注入器 `method=` 描述符（4 个 MC 版本 × 79 个） | **全部命中，0 缺失** |
| 外部库目标（Truffle × 2、GLFW × 1） | 预期盲区，显式跳过 |
| `@Shadow` 可见性差异 | 4 处（`VehicleRidingClientMixin.getViewOffset`，`private` vs 目标 `public`）⇒ **静默升级，非缺陷** |
| 结论 | **ante 不存在 turbo 1.1.7 那类描述符失效缺陷** |

## 仍然值得注意的事

虽然 `@Shadow` 可见性不致命，但**写窄仍有代价**：

1. 当声明为 `private` 时，Mixin 会把该方法的可见性**改写成目标的可见性**
   （`Bytecode.setVisibility`），也就是 mixin 类里那个 `private` 方法实际变成了 `public`；
   如果同一个 mixin 里有别的 `private` 同名方法，可能产生意外的重载/遮蔽。
2. 当声明为 `protected`/包私有且目标为 `public` 时，Mixin **什么都不做**，
   这意味着 mixin 类里该方法的可见性仍是 `protected`，
   而 `@Shadow` 方法体是 `throw new AssertionError()` —— 一旦有人忘了被注入的逻辑去调用它，
   运行期会抛 `AssertionError` 而不是编译错误。
3. 因此**最佳实践仍是让 `@Shadow` 的可见性与目标完全一致**：
   不是为了避免崩溃，而是为了让「mixin 声明的形状」和「真实目标」保持一致，减少认知负担。

## 复现方法

```powershell
# 1) 定位字符串
python -c "import zipfile;z=zipfile.ZipFile(r'<mixin jar>');print([n for n in z.namelist() if b'cannot reduce' in z.read(n)])"
# → org/spongepowered/asm/mixin/transformer/MixinPreProcessorStandard.class

# 2) 反编译
javap -c -p -cp <解出的 classes 根> org.spongepowered.asm.mixin.transformer.MixinPreProcessorStandard
```

`org.ow2.asm` / `mixin` 的 jar 位于
`C:\gradle-home\caches\modules-2\files-2.1\`。
