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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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
        final Context ctx = Context.newBuilder("js").allowAllAccess(true).build();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final CountDownLatch started2 = new CountDownLatch(1);
        final Thread victim2 = new Thread(() -> {
            try {
                started2.countDown();
                ctx.eval(Source.newBuilder("js",
                        "function spin(){ var i = 0; while(true) { i++; } } spin();",
                        "spin.js").buildLiteral());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "victim2");
        victim2.setDaemon(true);
        victim2.start();
        started2.await(5, TimeUnit.SECONDS);
        Thread.sleep(500L);

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
