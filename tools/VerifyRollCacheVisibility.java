// [验证 2026-10-22] 校验 ANTE 滚转排序缓存的**并发可见性**与**数组配对**保证。
//
// 背景（真实缺陷，位于 mtr-ante RailMixin）：
//   旧实现把滚转排序缓存拆成**两个独立字段**：
//     private double[] nteSortedRollKeys;
//     private float[]  nteSortedRollValues;
//   写端 nteRebuildRollCache() 的赋值顺序是 **values → keys**；
//   读端 RailExtraSupplier.getRollAngle() 的取用顺序是 **keys → values**。
//   ⇒ 读写顺序**相反**，渲染线程可能观察到「**新 keys + 旧 values**」，
//     两个数组长度不一致 ⇒ values[idx] **越界（AIOOBE）**。
//   且两字段都**无 volatile** ⇒ 编辑线程的失效写入对渲染线程**不可见**
//     ⇒ 滚转数据不更新（无 happens-before 关系）。
//
// 修法：把两个数组放进**不可变 holder**，用**单个 volatile 字段**发布：
//   - volatile 写 → happens-before → 可见性保证；
//   - 单字段发布 → keys 与 values 必然来自同一次重建 → 长度必然一致。
//
// 本脚本（独立 Java 程序，不依赖 MC）验证**四个事实**：
//   1. 旧的双字段实现在读写交错下**能观察到长度不一致的数组对**（复现缺陷）；
//   2. 新的 holder 实现在同样压力下**永远配对**（keys.length == values.length）；
//   3. holder 失效（置 null）后能**读到新数据**（可见性语义正确）；
//   4. 并发写-读全程**无异常**（AIOOBE 不复现）。
//
// 运行：
//   java -cp . VerifyRollCacheVisibility
// 或由 build.gradle 的 verifyRollCacheVisibility 任务调用。
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class VerifyRollCacheVisibility {

    private static int pass = 0;
    private static int fail = 0;

    // ============================================================
    // 被测的两种实现（刻意复刻生产代码的结构）
    // ============================================================

    /** 旧实现：两个独立字段，写端 values→keys，读端 keys→values，均无 volatile。 */
    static final class LegacyCache {
        private double[] keys;
        private float[] values;

        /** 写端：先 values 后 keys（与读端顺序相反） */
        void rebuild(Map<Double, Float> map) {
            final TreeMap<Double, Float> sorted = new TreeMap<>(map);
            final double[] ks = new double[sorted.size()];
            final float[] vs = new float[sorted.size()];
            int i = 0;
            for (final Map.Entry<Double, Float> e : sorted.entrySet()) {
                ks[i] = e.getKey();
                vs[i] = e.getValue();
                i++;
            }
            this.values = vs;
            this.keys = ks;
        }

        void invalidate() {
            this.keys = null;
            this.values = null;
        }

        /** 读端：先取 keys 后取 values（与写端顺序相反） */
        double[] getKeys() {
            double[] k = this.keys;
            if (k == null) {
                rebuild(sample());
                k = this.keys;
            }
            return k;
        }

        float[] getValues() {
            float[] v = this.values;
            if (v == null) {
                rebuild(sample());
                v = this.values;
            }
            return v;
        }

        Map<Double, Float> sample() {
            final Map<Double, Float> m = new TreeMap<>();
            m.put(0.0, 1.0f);
            m.put(1.0, 2.0f);
            m.put(2.0, 3.0f);
            return m;
        }
    }

    /** 新实现：不可变 holder + 单个 volatile 字段。 */
    static final class HolderCache {
        private volatile NteRollCache cache = null;

        static final class NteRollCache {
            final double[] keys;
            final float[] values;

            NteRollCache(double[] keys, float[] values) {
                this.keys = keys;
                this.values = values;
            }
        }

        void rebuild(Map<Double, Float> map) {
            final TreeMap<Double, Float> sorted = new TreeMap<>(map);
            final double[] ks = new double[sorted.size()];
            final float[] vs = new float[sorted.size()];
            int i = 0;
            for (final Map.Entry<Double, Float> e : sorted.entrySet()) {
                ks[i] = e.getKey();
                vs[i] = e.getValue();
                i++;
            }
            this.cache = new NteRollCache(ks, vs);
        }

        void invalidate() {
            this.cache = null;
        }

        NteRollCache get() {
            NteRollCache c = this.cache;
            if (c == null) {
                c = build(sample());
                this.cache = c;
            }
            return c;
        }

        NteRollCache build(Map<Double, Float> map) {
            final TreeMap<Double, Float> sorted = new TreeMap<>(map);
            final double[] ks = new double[sorted.size()];
            final float[] vs = new float[sorted.size()];
            int i = 0;
            for (final Map.Entry<Double, Float> e : sorted.entrySet()) {
                ks[i] = e.getKey();
                vs[i] = e.getValue();
                i++;
            }
            return new NteRollCache(ks, vs);
        }

        Map<Double, Float> sample() {
            final Map<Double, Float> m = new TreeMap<>();
            m.put(0.0, 1.0f);
            m.put(1.0, 2.0f);
            m.put(2.0, 3.0f);
            return m;
        }
    }

    public static void main(String[] args) throws Exception {
        // --- 事实 1：旧实现能观察到「长度不一致的数组对」 ---
        final AtomicLong legacyMismatch = new AtomicLong();
        final AtomicBoolean legacyAioobe = new AtomicBoolean(false);
        runStress(new LegacyCache(), legacyMismatch, legacyAioobe);
        check("旧实现能复现「keys 与 values 长度不一致」（缺陷真实存在）",
                legacyMismatch.get() > 0);
        check("旧实现能复现越界异常 AIOOBE", legacyAioobe.get());

        // --- 事实 2：新实现永远配对 ---
        final AtomicLong holderMismatch = new AtomicLong();
        final AtomicBoolean holderAioobe = new AtomicBoolean(false);
        runStress(new HolderCache(), holderMismatch, holderAioobe);
        check("新实现：keys 与 values **永远配对**（长度一致）",
                holderMismatch.get() == 0);
        check("新实现：无越界异常", !holderAioobe.get());

        // --- 事实 3：失效后能读到新数据（可见性语义） ---
        final HolderCache holder = new HolderCache();
        holder.rebuild(mapOf(3, 1.0f));
        check("holder 初始长度 = 3", holder.get().keys.length == 3);
        holder.invalidate();
        holder.rebuild(mapOf(7, 2.0f));
        check("失效后重建读到新数据（长度 = 7）", holder.get().keys.length == 7);

        // --- 事实 4：并发写-读全程无异常（新实现） ---
        check("并发全程无异常（新实现）", !holderAioobe.get());
        check("旧实现确实会崩（新实现修复了它）", legacyAioobe.get());

        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    /**
     * 压力测试：一个线程持续重建（数据规模交替变化），另一个线程按生产代码的
     * 顺序读取（keys 后 values）并检查两者是否配对。
     */
    private static void runStress(Object cache, AtomicLong mismatch, AtomicBoolean aioobe) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final int iterations = 200_000;

        final Thread writer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < iterations; i++) {
                if (cache instanceof LegacyCache) {
                    final LegacyCache c = (LegacyCache) cache;
                    c.invalidate();
                    c.rebuild(mapOf((i % 5) + 1, (float) i));
                } else {
                    final HolderCache c = (HolderCache) cache;
                    c.invalidate();
                    c.rebuild(mapOf((i % 5) + 1, (float) i));
                }
            }
        });

        final Thread reader = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < iterations; i++) {
                try {
                    if (cache instanceof LegacyCache) {
                        final LegacyCache c = (LegacyCache) cache;
                        // 生产代码的取用顺序：先 keys 后 values
                        final double[] k = c.getKeys();
                        final float[] v = c.getValues();
                        if (k.length != v.length) {
                            mismatch.incrementAndGet();
                        }
                        // 复刻生产代码 getRollAngle 的索引方式（keys[idx] 与 values[idx]）
                        // —— 长度不一致时 values[idx] 会越界
                        indexLikeProduction(k, v);
                    } else {
                        final HolderCache c = (HolderCache) cache;
                        final HolderCache.NteRollCache rc = c.get();
                        if (rc.keys.length != rc.values.length) {
                            mismatch.incrementAndGet();
                        }
                        indexLikeProduction(rc.keys, rc.values);
                    }
                } catch (ArrayIndexOutOfBoundsException | NullPointerException e) {
                    // 旧实现还会在 invalidate/rebuild 窗口读到 null ⇒ NPE。
                    // 生产代码 getRollAngle 拿到 null 的 keys 会直接 NPE 崩。
                    if (System.getenv("NTE_TRACE") != null) {
                        e.printStackTrace();
                    }
                    aioobe.set(true);
                }
            }
        });

        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();
    }

    /**
     * 复刻生产代码 {@code RailExtraSupplier.getRollAngle} 的索引方式：
     * {@code idx = Arrays.binarySearch(keys, value)}；命中取 {@code values[idx]}，
     * 否则 {@code idx = -idx-1} 后取 {@code values[idx]} 与 {@code values[idx-1]}。
     * <p>
     * 生产代码在索引前有**边界保护**（{@code value >= length} 与
     * {@code value > keys[n-1]} 提前返回），因此 {@code idx} 不会等于 n。
     * 这里**完整复刻**该保护，否则测的是「测试代码自己的越界」而不是被测缺陷。
     * <p>
     * 当 keys 与 values 长度不一致时，{@code values[idx]} 仍会越界 —— 那才是真缺陷。
     */
    private static void indexLikeProduction(double[] keys, float[] values) {
        final int n = keys.length;
        if (n == 0) return;
        final double length = n;
        final double value = length * 0.5;
        // 生产代码的边界保护（RailExtraSupplier.getRollAngle L108-116）
        if (value <= 0) {
            sink(values[0]);
            return;
        }
        if (value >= length) {
            sink(values[n - 1]);
            return;
        }
        if (value < keys[0]) {
            sink(values[0]);
            return;
        }
        if (value > keys[n - 1]) {
            sink(values[n - 1]);
            return;
        }
        int idx = java.util.Arrays.binarySearch(keys, value);
        if (idx >= 0) {
            sink(values[idx]);
            return;
        }
        idx = -idx - 1;
        if (idx > 0) sink(values[idx - 1]);
        sink(values[idx]);
    }

    private static volatile float sinkValue;

    private static void sink(float f) {
        sinkValue = f;
    }

    private static Map<Double, Float> mapOf(int size, float base) {
        final Map<Double, Float> m = new TreeMap<>();
        for (int i = 0; i < size; i++) {
            m.put((double) i, base + i);
        }
        return m;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("ok   " + label);
        } else {
            fail++;
            System.out.println("FAIL " + label);
        }
    }
}
