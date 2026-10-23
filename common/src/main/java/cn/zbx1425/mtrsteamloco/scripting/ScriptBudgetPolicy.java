package cn.zbx1425.mtrsteamloco.scripting;

/**
 * 脚本执行预算策略 —— <b>纯函数，零依赖</b>（不引用 MC / GraalVM）。
 * <p>
 * 为什么单独成类：{@link ScriptHolderBase} 依赖 MC 与 GraalVM，
 * 无法在离屏环境加载；而这段「预算怎么算」的逻辑恰恰是最需要回归的部分
 * （算错会让合法慢脚本被误杀，或让死循环被放生）。
 * 抽出来后 {@code tools/VerifyScriptTimeout.java} 可以直接调用<b>真代码</b>，
 * 不必复制一份参考实现 —— 避免测试与生产实现悄悄分叉。
 *
 * <h2>策略</h2>
 * <ul>
 *   <li><b>从未成功完成过</b>（{@code successfulRuns < }{@link #MIN_COMPLETIONS}，
 *       或没有成功耗时样本）⇒ 恒用 {@link #BASE_MILLIS}。
 *       死循环脚本第一次就爆，立刻被中断，<b>不会因为「自适应」被放生</b>。</li>
 *   <li><b>已有成功历史</b> ⇒ {@code clamp(SLOW_MULTIPLIER × 历史最长成功耗时,
 *       BASE_MILLIS, MAX_MILLIS)}。
 *       慢脚本按自己的历史水平拿到余量，不再被当作死循环砍掉。</li>
 * </ul>
 *
 * <p>
 * 只有「成功完成」的那次才应计入历史：失败/被中断的耗时若也计入，
 * 死循环脚本会把自己的超时时长当成「历史最长耗时」，从而不断抬高自己的预算
 * —— 自我放生。调用方必须只把成功路径写进 {@code maxObservedDurationNanos}。
 */
public final class ScriptBudgetPolicy {

    /** 基础预算：正常 render 回调远小于此值 */
    public static final long BASE_MILLIS = 10_000L;

    /** 上限：再长的单次调用已经不是「慢」而是设计问题，且 SCRIPT_THREAD 是单线程 */
    public static final long MAX_MILLIS = 60_000L;

    /**
     * 相对历史最长成功耗时的倍数。
     * <p>
     * 不用 2 倍：render 回调耗时抖动大（GC、区块加载、首次 JIT），2 倍容易反复误杀。
     * 4 倍仍把「慢」限制在一个数量级内。
     */
    public static final long SLOW_MULTIPLIER = 4L;

    /** 至少成功完成这么多次，才承认它是「慢」而不是「卡死」 */
    public static final int MIN_COMPLETIONS = 2;

    private ScriptBudgetPolicy() {
    }

    /**
     * @param successfulRuns           该脚本已成功完成的次数（不含失败/被中断）
     * @param maxObservedDurationNanos 历史最长<b>成功</b>耗时（纳秒）；{@code <= 0} 表示无样本
     * @return 本次调用允许的墙钟预算（毫秒），必在 [{@link #BASE_MILLIS}, {@link #MAX_MILLIS}] 内
     */
    public static long budgetMillis(int successfulRuns, long maxObservedDurationNanos) {
        if (successfulRuns < MIN_COMPLETIONS || maxObservedDurationNanos <= 0L) {
            return BASE_MILLIS;
        }
        final long scaled = (maxObservedDurationNanos * SLOW_MULTIPLIER) / 1_000_000L;
        return Math.min(MAX_MILLIS, Math.max(BASE_MILLIS, scaled));
    }
}
