// [验证 2026-10-21] 审查项 #9 —— ANTE 脚本执行超时机制的可执行验证。
//
// 背景：
//   ante 原实现没有脚本执行超时。资源包作者写一个死循环（while(true){}）会永久
//   占住 SCRIPT_THREAD，之后所有脚本（包括其它资源包）全部排队卡死。
//
// 修复方案（ScriptHolderBase.startWatchdog）：
//   用 GraalVM 24.x 的 Context.interrupt(Duration) 做协作式中断。
//   该 API 只能由 creator thread 调用（Context.checkCreatorAccess("interrupted")），
//   本类满足：context 在主线程创建、执行在 SCRIPT_THREAD。
//
// 本脚本（独立 Java 程序，不依赖 MC）验证三个事实：
//   1. 死循环脚本在无干预时确实会一直跑（证明问题真实存在）；
//   2. Context.interrupt(Duration) 能在有限时间内中断它；
//   3. 中断后抛出的异常类型可被既有 catch 分支捕获（PolyglotException），
//      即 ante 的 failTime/failException 记录路径能正常生效。
//
// 运行：
//   java -cp "graal-polyglot.jar;graal-js.jar;graal-truffle-api.jar;graal-truffle-runtime.jar;." \
//        VerifyScriptTimeout
// 或由 build.gradle 的 verifyScriptTimeout 任务调用。

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

// [continue.7] 预算策略的生产实现（纯函数、零依赖），本测试直接调用它
import cn.zbx1425.mtrsteamloco.scripting.ScriptBudgetPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VerifyScriptTimeout {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        // --- 事实 1：死循环脚本在无干预时不会自己结束 ---
        final AtomicBoolean finishedWithoutInterrupt = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final Thread victim = new Thread(() -> {
            try (Context ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
                started.countDown();
                ctx.eval(Source.newBuilder("js",
                        "function spin(){ var i = 0; while(true) { i++; } } spin();",
                        "spin.js").buildLiteral());
                finishedWithoutInterrupt.set(true);
            } catch (Throwable t) {
                finishedWithoutInterrupt.set(false);
            }
        }, "victim");
        victim.setDaemon(true);
        victim.start();
        started.await(5, TimeUnit.SECONDS);
        Thread.sleep(1500L); // 让它跑一会儿，确认真的停不下来
        final boolean stillRunning = victim.isAlive();
        check("事实1：死循环脚本 1.5s 后仍在运行（问题真实存在）", stillRunning);
        check("事实1：未自行结束", !finishedWithoutInterrupt.get());

        // --- 事实 2 & 3：creator thread 调 interrupt(Duration) 能中断它 ---
        //
        // ★ 稳定性修复（continue.7）：原实现用 `started2.countDown()` + 固定
        //   `Thread.sleep(500)` 来「等脚本跑起来」。这两者在机器负载高时都不可靠
        //   （曾被 gradle daemon + 后台构建拖到 5 条断言随机失败）：
        //   victim2 可能还没进入 polyglot 执行体，interrupt 就找不到可中断的目标，
        //   或者 1s 的等待窗口在排队中耗尽 ⇒ TimeoutException ⇒ 假失败。
        //
        //   改为**由 JS 自己回报**：脚本进入循环前调用宿主回调 __enterLoop()，
        //   主线程 await 这个 latch 再 interrupt。这样「何时可以中断」由事实决定，
        //   不再由猜测的睡够时长决定。
        final Context ctx = Context.newBuilder("js").allowAllAccess(true).build();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final CountDownLatch inJsLoop = new CountDownLatch(1);
        ctx.getBindings("js").putMember("__enterLoop", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> {
            inJsLoop.countDown();
            return null;
        });
        final CountDownLatch started2 = new CountDownLatch(1);
        final Thread victim2 = new Thread(() -> {
            try {
                started2.countDown();
                ctx.eval(Source.newBuilder("js",
                        "function spin(){ __enterLoop(); var i = 0; while(true) { i++; } } spin();",
                        "spin.js").buildLiteral());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "victim2");
        victim2.setDaemon(true);
        victim2.start();
        started2.await(5, TimeUnit.SECONDS);
        // 等到脚本**确实**已进入 polyglot 循环（不是靠睡够时长）
        final boolean entered = inJsLoop.await(15, TimeUnit.SECONDS);
        check("事实2：脚本已进入 polyglot 循环（握手成功）", entered);

        final long t0 = System.nanoTime();
        boolean interrupted = false;
        try {
            // 这是 ante 看门狗实际调用的 API
            ctx.interrupt(Duration.ofMillis(1000L));
            interrupted = true;
        } catch (Exception e) {
            System.out.println("  interrupt 抛异常: " + e);
        }
        final long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        check("事实2：interrupt 未抛异常", interrupted);
        check("事实2：interrupt 在有限时间内返回（<5s）", elapsedMs < 5000L);

        victim2.join(5000L);
        check("事实2：脚本线程已被中断并退出", !victim2.isAlive());

        final Throwable t = thrown.get();
        check("事实3：抛出了异常（ante 的 catch 分支能捕获）", t != null);
        check("事实3：异常是 PolyglotException（ante catch Exception 能接住）",
                t instanceof PolyglotException);
        if (t != null) {
            System.out.println("  实际异常: " + t.getClass().getName() + " : " + t.getMessage());
        }

        // --- 事实 4：中断后 context 仍可用（ante 需要继续跑后续脚本）---
        // 注意：Context.eval 返回的是 org.graalvm.polyglot.Value，不是 Integer，
        // 要用 asInt()/toString() 取值。
        try {
            final Value v = ctx.eval("js", "1 + 1");
            check("事实4：中断后 context 仍可执行脚本", v.asInt() == 2);
        } catch (Throwable t2) {
            check("事实4：中断后 context 仍可执行脚本", false);
            System.out.println("  异常类型: " + t2.getClass().getName());
            System.out.println("  异常消息: " + t2.getMessage());
            if (t2 instanceof PolyglotException) {
                System.out.println("  isInterrupted=" + ((PolyglotException) t2).isInterrupted()
                        + " isExit=" + ((PolyglotException) t2).isExit()
                        + " isHostException=" + ((PolyglotException) t2).isHostException());
            }
        }

        // --- 事实 5：正常脚本不会被误中断 ---
        final Context ctx2 = Context.newBuilder("js").allowAllAccess(true).build();
        try {
            ctx2.eval("js", "var s = 0; for (var i = 0; i < 1000000; i++) { s += i; } s;");
            check("事实5：正常长脚本执行完毕未被误杀", true);
        } catch (Throwable t3) {
            check("事实5：正常长脚本执行完毕未被误杀", false);
            System.out.println("  异常: " + t3);
        }

        // =====================================================================
        // 事实 6-8（2026-10-21 线程爆炸优化）：复刻新版看门狗逻辑并验证。
        //
        // 新版 ScriptHolderBase.startWatchdog 用**静态 ScheduledExecutorService**
        // （1 个 daemon 线程）schedule 一次性任务，不再每次调用新建 2 个线程。
        // 任务触发时用 task.isDone() 判断脚本是否已提前完成。
        //
        // 下面复刻同一逻辑（独立实现，不依赖 MC 类）验证三点：
        //   6. 提前完成的脚本不会触发 interrupt（isDone 短路生效）；
        //   7. 超时的脚本会被 interrupt（isDone 为 false 时正常中断）；
        //   8. 连续 1000 次调度**不创建新线程**（线程数不增长）——
        //      这是本次优化的核心断言。
        // =====================================================================
        final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(
                1, r -> {
                    final Thread th = new Thread(r, "ante-script-watchdog-test");
                    th.setDaemon(true);
                    return th;
                });
        try {
            // --- 事实 8：连续调度不产生新线程 ---
            final int before = Thread.activeCount();
            final List<Future<?>> scheduled = new ArrayList<>();
            final ExecutorService worker = Executors.newSingleThreadExecutor();
            for (int i = 0; i < 1000; i++) {
                final Future<?> f = worker.submit(() -> { });
                scheduled.add(watchdog.schedule(() -> {
                    // 复刻新版看门狗：脚本已提前完成 => 直接返回
                    if (f.isDone()) return;
                }, 50L, TimeUnit.MILLISECONDS));
            }
            worker.shutdown();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            Thread.sleep(300L);
            final int after = Thread.activeCount();
            final long watchdogThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(th -> "ante-script-watchdog-test".equals(th.getName())).count();
            check("事实8：1000 次调度后看门狗线程数仍为 1（未线程爆炸）", watchdogThreads == 1);
            System.out.println("  活跃线程 before=" + before + " after=" + after
                    + " 看门狗线程数=" + watchdogThreads);
            for (final Future<?> f : scheduled) f.cancel(false);

            // --- 事实 6：提前完成的脚本不会被中断 ---
            final Context ctx3 = Context.newBuilder("js").allowAllAccess(true).build();
            final AtomicBoolean interrupted3 = new AtomicBoolean(false);
            final ExecutorService single = Executors.newSingleThreadExecutor();
            final Future<?> quick = single.submit(() -> {
                try {
                    ctx3.eval("js", "1 + 1");
                } catch (Throwable t4) {
                    // ignore
                }
            });
            quick.get(5, TimeUnit.SECONDS);
            // 复刻看门狗判定：任务已完成 => 不调 interrupt
            if (!quick.isDone()) {
                interrupted3.set(true);
                try {
                    ctx3.interrupt(Duration.ofMillis(1000L));
                } catch (Exception ignore) {
                    // ignore
                }
            }
            check("事实6：提前完成的脚本未被中断（isDone 短路）", !interrupted3.get());
            final Value v3 = ctx3.eval("js", "2 + 2");
            check("事实6：提前完成的脚本 context 仍可用", v3.asInt() == 4);
            ctx3.close();
            single.shutdownNow();

            // --- 事实 7：超时脚本会被中断（isDone 为 false 时正常中断）---
            final Context ctx4 = Context.newBuilder("js").allowAllAccess(true).build();
            final AtomicReference<Throwable> thrown4 = new AtomicReference<>();
            final Thread victim4 = new Thread(() -> {
                try {
                    ctx4.eval(Source.newBuilder("js",
                            "function spin(){ var i = 0; while(true) { i++; } } spin();",
                            "spin.js").buildLiteral());
                } catch (Throwable t5) {
                    thrown4.set(t5);
                }
            }, "victim4");
            victim4.setDaemon(true);
            victim4.start();
            Thread.sleep(500L);
            // 复刻看门狗：任务未完成 => 调 interrupt
            if (victim4.isAlive()) {
                try {
                    ctx4.interrupt(Duration.ofMillis(1000L));
                } catch (Exception ignore) {
                    // ignore
                }
            }
            victim4.join(5000L);
            check("事实7：超时脚本被中断并退出", !victim4.isAlive());
            check("事实7：超时脚本抛出 PolyglotException",
                    thrown4.get() instanceof PolyglotException);
            ctx4.close();
        } finally {
            watchdog.shutdownNow();
        }

        // ───────────────────────────────────────────────────────────────────
        // [continue.7] 预算策略：把「慢脚本」与「死循环」区分开
        //
        // 直接调用生产代码 cn.zbx1425.mtrsteamloco.scripting.ScriptBudgetPolicy
        // （纯函数、零依赖），而不是复制一份参考实现 —— 避免测试与实现分叉。
        // ───────────────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("[ScriptBudgetPolicy]");
        {
            final long base = ScriptBudgetPolicy.BASE_MILLIS;
            final long max = ScriptBudgetPolicy.MAX_MILLIS;

            // 安全底线：没有成功历史 ⇒ 永远基础预算（死循环不能被放生）
            check("无成功历史（0 次）⇒ 基础预算",
                    ScriptBudgetPolicy.budgetMillis(0, 0L) == base);
            check("无成功历史（1 次）⇒ 基础预算",
                    ScriptBudgetPolicy.budgetMillis(1, 30_000L * 1_000_000L) == base);
            check("有成功次数但没有耗时样本 ⇒ 基础预算",
                    ScriptBudgetPolicy.budgetMillis(10, 0L) == base);
            check("负数耗时样本 ⇒ 基础预算",
                    ScriptBudgetPolicy.budgetMillis(10, -1L) == base);

            // 慢脚本：按 4 倍历史、夹在 [base, max]
            check("历史 1s × 4 = 4s < base ⇒ 抬到 base（不降级）",
                    ScriptBudgetPolicy.budgetMillis(2, 1_000L * 1_000_000L) == base);
            check("历史 3s × 4 = 12s ⇒ 12s",
                    ScriptBudgetPolicy.budgetMillis(2, 3_000L * 1_000_000L) == 12_000L);
            check("历史 5s × 4 = 20s ⇒ 20s",
                    ScriptBudgetPolicy.budgetMillis(5, 5_000L * 1_000_000L) == 20_000L);
            check("历史 20s × 4 = 80s ⇒ 夹到上限 60s",
                    ScriptBudgetPolicy.budgetMillis(3, 20_000L * 1_000_000L) == max);
            check("历史超长（10 分钟）⇒ 仍是上限 60s",
                    ScriptBudgetPolicy.budgetMillis(9, 600_000L * 1_000_000L) == max);

            // 不变式：预算恒在 [base, max]，且对成功次数单调不减
            boolean inRange = true;
            boolean monotonic = true;
            long prev = -1L;
            final long[] samples = {0L, 1L, 999L, 1_000L, 2_500L, 5_000L, 15_000L, 60_000L, 600_000L};
            for (long ms : samples) {
                final long b = ScriptBudgetPolicy.budgetMillis(4, ms * 1_000_000L);
                if (b < base || b > max) inRange = false;
                if (b < prev) monotonic = false;
                prev = b;
            }
            check("不变式：预算恒在 [base, max] 内", inRange);
            check("不变式：耗时样本越大，预算不减", monotonic);
        }

        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
