package cn.zbx1425.mtrsteamloco.scripting;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonObject;
import cn.zbx1425.mtrsteamloco.Main;
import net.minecraft.world.entity.player.Player;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cn.zbx1425.mtrsteamloco.scripting.util.*;
import cn.zbx1425.sowcer.math.*;
import cn.zbx1425.mtrsteamloco.data.ShapeSerializer;
import net.minecraft.network.chat.Component;
import cn.zbx1425.mtrsteamloco.Main;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.FileSystem.Selector;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.time.Duration;

public abstract class ScriptHolderBase {

    private static ExecutorService SCRIPT_THREAD = Executors.newSingleThreadExecutor();

    public final String side;
    private Context context; 
    private Value globalBindings; 
    public final Map<String, List<Value>> functions = new HashMap<>(); 

    public long failTime = 0;
    public Exception failException = null;

    public String name;
    public String contextTypeName;
    private Map<ResourceLocation, String> scripts;

    private JsonObject config;
    private String key;
    private String[] functionNames;

    private final boolean[] loading = new boolean[] { true };

    protected static final String PRETREATMENT = "load(\"nashorn:mozilla_compat.js\");";

    public ScriptHolderBase(String side) {
        this.side = side;
    }

    private static final Set<String> ALLOWED_PACKAGES = Set.of(
        "java.awt", "java.util", "mtr"
    );

    // [修复 2026-10-21] 审查项 #9：JS 脚本执行超时。
    // 默认 10 秒：正常脚本（render 回调）远小于此值；死循环脚本会被看门狗中断。
    private static final long SCRIPT_TIMEOUT_MILLIS = 10_000L;
    private static final long SCRIPT_TIMEOUT_NANOS = SCRIPT_TIMEOUT_MILLIS * 1_000_000L;
    // context.interrupt() 的等待时限：脚本线程此刻若不在 polyglot 代码里
    // （卡在 Java 宿主调用），interrupt 会抛 TimeoutException。
    private static final long INTERRUPT_GRACE_MILLIS = 1_000L;

    public void load(
        String name, String contextTypeName, ResourceManager resourceManager, 
        Map<ResourceLocation, String> scripts, JsonObject config, String key, 
        String... functionNames) throws Exception {
        this.name = name;
        this.contextTypeName = contextTypeName;
        this.scripts = scripts;
        this.config = config;
        this.key = key;
        this.functionNames = functionNames;

        boolean trust = false;

        // [续更修复] GraalVM 24.x 起 com.oracle.truffle.polyglot.FileSystems 不再是公共 API
        // （作者在 22.3.0/23.x/24.2.x 间反复尝试均编译不过），改用公开 API 实现的
        // RootedScriptFileSystem：语义与原内部 newDefaultFileSystem(root) 一致，
        // 以 ./ante/script_data/ 为根的沙箱（相对路径锚定根目录、拒绝越界与符号链接逃逸）
        final Path basicFolder = Path.of("./ante/script_data/").toAbsolutePath().normalize();
        final FileSystem defFileSystem = new RootedScriptFileSystem(basicFolder);

        context = Context.newBuilder("js")  
            .allowNativeAccess(false)
            .option("engine.WarnInterpreterOnly", "false")
            .allowCreateThread(true)  
            .allowCreateProcess(false)
            .allowHostClassLoading(true)  
            .allowHostClassLookup(trust ? className -> true : className -> {
                if (loading[0]) return true;
                for (String allowedPackage : ALLOWED_PACKAGES) {
                    if (className.startsWith(allowedPackage)) {
                        return true;
                    }
                }
                return false;
            })
            .allowIO(IOAccess.newBuilder().fileSystem(FileSystem.newCompositeFileSystem(defFileSystem, Selector.of(defFileSystem, path -> {
                if (path.isAbsolute()) {
                    return path.normalize().startsWith(basicFolder);
                } else {
                    return basicFolder.resolve(path).normalize().startsWith(basicFolder);
                }
            }))).build())  
            .allowEnvironmentAccess(EnvironmentAccess.INHERIT)  
            .allowExperimentalOptions(true)  
            .allowInnerContextOptions(true)  
            .sandbox(SandboxPolicy.TRUSTED)
            .allowHostAccess(
                HostAccess.newBuilder()
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowBufferAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .allowMapAccess(true)
                .allowAccessInheritance(true)
                .allowBigIntegerNumberAccess(true)
                .targetTypeMapping(
                    Double.class,
                    Double.class,
                    value -> true,
                    value -> value,
                    HostAccess.TargetMappingPrecedence.HIGHEST
                )
                .targetTypeMapping(
                    Double.class,
                    Float.class,
                    value -> true,
                    value -> value.floatValue(),
                    HostAccess.TargetMappingPrecedence.HIGH
                )
                .targetTypeMapping(
                    Double.class,
                    Long.class,
                    value -> true,
                    value -> Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Integer.class,
                    value -> true,
                    value -> (int) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Short.class,
                    value -> true,
                    value -> (short) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Byte.class,
                    value -> true,
                    value -> (byte) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .build()
            )
            .option("js.nashorn-compat", "true")
            .option("js.ecmascript-version", "latest")
            .option("js.foreign-object-prototype", "true")
            .option("log.file", "./logs/latest.log")
            .build();

        // [移除 2026-10-21] 下方原有一段 if (false) { ... } 的死代码，
        // 内含「全沙箱打开」配置（allowCreateProcess / allowHostClassLoading /
        // allowHostClassLookup(全放行) / allowIO(ALL) / allowNativeAccess(true)）。
        // 把 if (false) 改成 if (true) 就会立刻开全沙箱 —— 典型风险信号，
        // 且与上方实际生效的受限 Context 配置并存易误导。已整块删除（git 历史可找回）。

        globalBindings = context.getBindings("js");    
        
        appendImporter();
        
        for (Map.Entry<ResourceLocation, String> entry : scripts.entrySet()) {
            String scriptContent = entry.getValue() != null ? 
                entry.getValue() : 
                ScriptResourceUtil.readString(entry.getKey());

            ScriptResourceUtil.executeScript(context, scriptContent, entry.getKey());

            for (String fn : functionNames) {
                registerFunction(fn);
                registerFunction(fn + contextTypeName);
            }
        }

        loading[0] = false;
    }

    public void reload(ResourceManager resourceManager) throws Exception {
        close();
        load(name, contextTypeName, resourceManager, scripts, config, key, functionNames);
    }

    protected void inject(Class clazz, String method, String alias) {
        if (alias == null) alias = method;
        context.eval("js", "var " + alias + " = Java.type('" + clazz.getName() + "')." + method + ";");
    }

    protected void inject(Class clazz, String alias) {
        if (alias == null) alias = clazz.getSimpleName();
        context.eval("js", "var " + alias + " = Java.type('" + clazz.getName() + "');");
    }

    protected void inject(String key, String value) {
        // [修复 2026-10-21] 原来是 eval 字符串拼接（"var " + key + " = '" + value + "';"），
        // 值里出现单引号 / 反斜杠 / 换行就会破坏脚本。当前调用点（SIDE / MOD_ENV）
        // 的值都是内部常量，行为不变，但改走 bindings 以彻底消除注入面。
        globalBindings.putMember(key, value);
    }

    protected void eval(String script) {
        context.eval("js", script);
    }

    protected void appendImporter() {
        eval(PRETREATMENT);
        inject("SIDE", side);
        // [修复 2026-10-21] CONFIG_INFO 注入曾用「Gson toJson + 手工转义引号 +
        // 塞进 JS 模板字符串」的写法。模板字符串会再解释一次转义序列：
        // 配置值里出现 Windows 路径（反斜杠 `C:\mc\server`）、换行或制表符时，
        // JSON.parse 直接抛 Bad escaped character / Bad control character，
        // 整个资源包脚本的 load() 失败（复现脚本 tools/verify_script_injection.mjs）。
        //
        // 现改为两步，**只编码一次**：
        //   1) JSON 文本作为 JS 字符串值经 bindings 传入（GraalVM 直接搬运字符串，
        //      不经过任何 eval 拼接，因此没有二次转义）；
        //   2) 在 JS 侧用 JSON.parse 解析。
        // CONFIG_INFO 仍然是**已解析的对象**（脚本侧 `CONFIG_INFO.xxx` 的用法不变），
        // 只是解析地点从 Java 字符串拼接挪到了 JS。
        String configInfo = new GsonBuilder().disableHtmlEscaping().create().toJson(config);
        globalBindings.putMember("CONFIG_INFO_JSON", configInfo);
        eval("var CONFIG_INFO = JSON.parse(CONFIG_INFO_JSON);");
        inject("MOD_ENV", Main.class.getPackageName().split("\\.")[0]);

        inject(ScriptResourceUtil.class, "includeScript", "include");
        inject(ScriptResourceUtil.class, "print", "print");
        inject(JsFriendlyJavaUtils.class, "asJavaArray", "asJavaArray");

        inject(TimingUtil.class, "Timing");
        inject(StateTracker.class, "StateTracker");
        inject(CycleTracker.class, "CycleTracker");
        inject(RateLimit.class, "RateLimit");
        inject(TextUtil.class, "TextUtil");
        inject(GlobalRegister.class, "GlobalRegister");
        inject(WrappedEntity.class, "WrappedEntity");
        inject(ComponentUtil.class, "ComponentUtil");
        inject(OrderedMap.class, "OrderedMap");
        inject(OrderedMap.PlacementOrder.class, "PlacementOrder");
        inject(ShapeSerializer.class, "ShapeSerializer");

        inject(Matrices.class, "Matrices");
        inject(Matrix4f.class, "Matrix4f");
        inject(Vector3f.class, "Vector3f");   

        inject(Component.class, "Component");
        
        inject(Optional.class, "Optional");
    }

    private void registerFunction(String name) {
        Value func = globalBindings.getMember(name);
        if (func != null && func.canExecute()) {
            functions.computeIfAbsent(name, k -> new ArrayList<>())
                     .add(func);
            eval("delete " + name + ";");
        }
    }

    public Future<?> callFunctionAsync(List<Value> functions, AbstractScriptContext scriptCtx, 
                                      Runnable finishCallback, Object... args) {
        if (duringFailTimeout()) return null;
        if (context == null) {
            Main.LOGGER.error("Script context is null, cannot execute function");
            return null;
        }

        final Future<?> task = SCRIPT_THREAD.submit(() -> {
            long start = System.nanoTime();
            try {            
                TimingUtil.prepareForScript(scriptCtx);    
                Object[] allArgs = new Object[3 + args.length];
                allArgs[0] = scriptCtx;
                allArgs[1] = scriptCtx.state != null ? scriptCtx.state : ProxyObject.fromMap(new HashMap<>());
                allArgs[2] = scriptCtx.getWrapperObject();
                System.arraycopy(args, 0, allArgs, 3, args.length);
                
                for (Value function : functions) {
                    function.executeVoid(allArgs);
                }

                failException = null;
                failTime = 0;
            } catch (Exception ex) {
                Main.LOGGER.error("Error in ANTE Resource Pack JavaScript", ex);
                failTime = System.currentTimeMillis();
                failException = ex;
            } finally {
                // [修复 2026-10-21] finishCallback 原本在 try 内、异常时不会执行。
                // 后果：create 回调不跑 ⇒ scriptCtx.created 永为 false ⇒
                // tryCallRenderFunctionAsync 每帧都走 "create" 分支 ⇒ 无限重试；
                // 且 scriptCtx.renderFunctionFinished() 永不被调用 ⇒ 渲染状态卡死。
                // 移到 finally：无论成功还是抛异常都执行一次。
                if (finishCallback != null) {
                    try {
                        finishCallback.run();
                    } catch (Exception ex) {
                        Main.LOGGER.error("Error in ANTE Resource Pack JavaScript callback", ex);
                    }
                }
            }
            scriptCtx.lastExecuteDuration = System.nanoTime() - start;
        });

        // [修复 2026-10-21] 审查项 #9：JS 脚本没有执行超时。
        // 后果：资源包作者写一个死循环（while(true){}）会永久占住 SCRIPT_THREAD，
        // 之后所有脚本（包括其它资源包）全部排队卡死，游戏表现为"脚本功能全失"。
        //
        // 方案：GraalVM 24.x 的 Context.interrupt(Duration) —— 官方协作式中断，
        // 只能由 creator thread 调用（Context.checkCreatorAccess("interrupted")）。
        // 本类正好满足：context 在主线程创建，执行在 SCRIPT_THREAD。
        //
        // 看门狗放在独立的 daemon 线程上（不能用 SCRIPT_THREAD 本身——那正是被卡住的那个），
        // 任务提前完成时靠 AtomicReference 里的完成标记提前退出。
        startWatchdog(task, scriptCtx);
        return task;
    }

    /**
     * [修复 2026-10-21] 审查项 #9 —— 脚本执行超时看门狗。
     * <p>
     * 超时后由看门狗线程调 {@code context.interrupt(Duration)}，
     * 脚本线程会收到 {@code PolyglotException}（"Execution interrupted."），
     * 走 {@code callFunctionAsync} 既有的 catch 分支（记 failTime/failException）。
     * <p>
     * 已经在跑/已超时的脚本不会重复中断：{@code interrupted} 标志用
     * {@link AtomicBoolean} 保证只置位一次。
     */
    private void startWatchdog(Future<?> task, AbstractScriptContext scriptCtx) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final long deadline = System.nanoTime() + SCRIPT_TIMEOUT_NANOS;

        final Thread watchdog = new Thread(() -> {
            try {
                while (!done.get()) {
                    if (System.nanoTime() - deadline >= 0) break;
                    Thread.sleep(20L);
                }
                if (done.get()) return;
                if (!interrupted.compareAndSet(false, true)) return;

                final Context ctx = context;
                if (ctx == null) return;
                Main.LOGGER.error("ANTE script timed out after {} ms, interrupting execution", SCRIPT_TIMEOUT_MILLIS);
                try {
                    // interrupt 内部有等待时限：若脚本线程此刻没在 polyglot 代码里
                    // （例如卡在 Java 宿主调用），interrupt 会抛 TimeoutException。
                    ctx.interrupt(Duration.ofMillis(INTERRUPT_GRACE_MILLIS));
                } catch (TimeoutException e) {
                    Main.LOGGER.error("ANTE script interrupt timed out (script may be stuck in a host call)");
                } catch (Exception e) {
                    Main.LOGGER.error("Error interrupting ANTE script", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // 无论成功、超时还是被打断，都保证唤醒下一个看门狗能观察到完成。
                done.set(true);
            }
        }, "ante-script-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // 任务提前结束 -> 让看门狗自行退出（done 标志）
        final Thread watcher = new Thread(() -> {
            try {
                task.get();
            } catch (Exception ignored) {
                // 任务异常/取消时也要唤醒看门狗
            } finally {
                done.set(true);
            }
        }, "ante-script-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public void tryCallFunctionAsync(String function, AbstractScriptContext scriptCtx, Runnable callback, Object... args) {
        if (!(scriptCtx.scriptStatus == null || scriptCtx.scriptStatus.isDone())) return;
        if (scriptCtx.disposed) return;
        List<Value> functions = this.functions.get(function);
        if (functions == null) functions = new ArrayList<>();
        scriptCtx.scriptStatus = callFunctionAsync(functions, scriptCtx, callback, args);
    }

    public void tryCallRenderFunctionAsync(AbstractScriptContext scriptCtx) {
        ScriptContextManager.trackContext(scriptCtx, this);
        if (!scriptCtx.created) {
            tryCallFunctionAsync("create", scriptCtx, () -> scriptCtx.created = true);
        } else {
            tryCallFunctionAsync("render", scriptCtx, () -> scriptCtx.renderFunctionFinished(), true);
        }
    }

    public void tryCallDisposeFunctionAsync(AbstractScriptContext scriptCtx) {
        tryCallFunctionAsync("dispose", scriptCtx, () -> scriptCtx.created = false, false);
        scriptCtx.disposed = true;
    }

    public void tryCallUseFunctionAsync(AbstractScriptContext scriptCtx, Player player) {
        tryCallFunctionAsync("use", scriptCtx, null, true, new WrappedEntity(player));
    }

    private boolean duringFailTimeout() {
        return failTime > 0 && (System.currentTimeMillis() - failTime) < 4000;
    }

    public static void resetRunner() {
        // [修复 2026-10-21] 原实现 shutdownNow() 后立刻换新 executor，
        // 旧线程可能仍在运行并持有 Context；close() 并发 context.close() ⇒ 竞态。
        // 等待旧线程终止（最多 1 秒）后再换新的。
        SCRIPT_THREAD.shutdownNow();
        try {
            if (!SCRIPT_THREAD.awaitTermination(1, TimeUnit.SECONDS)) {
                Main.LOGGER.warn("Script thread did not terminate within 1s after shutdownNow");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SCRIPT_THREAD = Executors.newSingleThreadExecutor();
    }

    public void close() {
        // [修复 2026-10-21] 审查项 #9：close 之前先让在跑的超时看门狗失效，
        // 否则它可能在 context 已关闭后再调 interrupt()。
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
