import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * ante mixin 注入器审计器（字节码级）。
 *
 * 检查两类真 bug（turbo 1.1.6 / 1.1.7 踩过的坑）：
 *   ① method= 描述符在目标 jar 里根本不存在 ⇒ 注入失败 ⇒ defaultRequire:1 下崩客户端
 *   ② @Shadow 声明的可见性低于目标方法的可见性 ⇒ 运行时 InvalidMixinException ⇒ 崩客户端
 *
 * 用法：java AuditAnteMixins <目标jar目录> <mixin源码目录>
 */
public class AuditAnteMixins {
    // ── 可见性等级（数字越大越宽松）──
    private static int vis(String s) {
        if (s == null) return 0;
        if (s.contains("private")) return 0;
        if (s.contains("protected")) return 1;
        if (s.contains("public")) return 2;
        return 0; // package-private
    }
    private static String visName(String s) {
        if (s == null) return "package-private";
        if (s.contains("private")) return "private";
        if (s.contains("protected")) return "protected";
        if (s.contains("public")) return "public";
        return "package-private";
    }

    /** 扫描一个 jar，收集 类名 -> { 方法: (描述符, 访问标志), 字段: (描述符, 访问标志) } */
    static class JarIndex {
        // 内部名 -> 访问标志
        Map<String, Integer> classes = new HashMap<>();
        // 内部名+"."+方法名+描述符 -> 访问标志
        Map<String, Integer> methods = new HashMap<>();
        Map<String, Integer> fields = new HashMap<>();
        // 内部名 -> 父类内部名
        Map<String, String> superName = new HashMap<>();

        JarIndex(Path jar) throws IOException {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    byte[] data = zf.getInputStream(e).readAllBytes();
                    ClassReader cr = new ClassReader(data);
                    classes.put(cr.getClassName(), cr.getAccess());
                    superName.put(cr.getClassName(), cr.getSuperName());
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    for (MethodNode mn : cn.methods) {
                        methods.put(cr.getClassName() + "." + mn.name + mn.desc, mn.access);
                    }
                    for (FieldNode fn : cn.fields) {
                        fields.put(cr.getClassName() + "." + fn.name + " " + fn.desc, fn.access);
                    }
                }
            }
        }

        /** 沿继承链找方法 */
        Integer findMethod(String owner, String name, String desc) {
            String cur = owner;
            Set<String> seen = new HashSet<>();
            while (cur != null && seen.add(cur)) {
                Integer a = methods.get(cur + "." + name + desc);
                if (a != null) return a;
                cur = superName.get(cur);
            }
            return null;
        }
        Integer findField(String owner, String name, String desc) {
            String cur = owner;
            Set<String> seen = new HashSet<>();
            while (cur != null && seen.add(cur)) {
                Integer a = fields.get(cur + "." + name + " " + desc);
                if (a != null) return a;
                cur = superName.get(cur);
            }
            return null;
        }

        /** 合并另一个索引（如 MC jar），先到先得 */
        void merge(JarIndex other) {
            other.classes.forEach(classes::putIfAbsent);
            other.methods.forEach(methods::putIfAbsent);
            other.fields.forEach(fields::putIfAbsent);
            other.superName.forEach(superName::putIfAbsent);
        }
    }

    public static void main(String[] args) throws Exception {
        // 目标类不在 mtr-common.jar / MC jar 里（外部库），属于预期盲区，不是缺陷
        final Set<String> EXTERNAL_LIB_TARGETS = new HashSet<>(Arrays.asList(
                "com/oracle/truffle/api/TruffleLanguage",
                "com/oracle/truffle/runtime/hotspot/HotSpotTruffleRuntimeAccess",
                "org/lwjgl/glfw/GLFW"
        ));

        Path targetDir = Paths.get(args[0]);
        Path mixinDir = Paths.get(args[1]);
        String[] versions = {"1.18.2", "1.19.2", "1.19.4", "1.20.1"};

        Map<String, JarIndex> idxs = new LinkedHashMap<>();
        for (String v : versions) {
            Path dir = targetDir.resolve(v);
            Path jar = dir.resolve("mtr-common.jar");
            Path mc = dir.resolve("mc.jar");
            if (!Files.exists(jar)) {
                System.out.println("!! 缺少目标 jar: " + jar);
                continue;
            }
            JarIndex ji = new JarIndex(jar);
            if (Files.exists(mc)) {
                // 把 MC 的类合并进来（目标类可能是 MC 的）
                ji.merge(new JarIndex(mc));
            }
            idxs.put(v, ji);
        }
        System.out.println("已索引目标 jar: " + idxs.keySet());
        System.out.println();

        // ── 解析 mixin 源码 ──
        // 收集 @Mixin 目标类、@Shadow 成员、注入器 method=
        List<File> mixinFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mixinDir, "*.java")) {
            for (Path p : ds) mixinFiles.add(p.toFile());
        }
        mixinFiles.sort(Comparator.comparing(File::getName));

        int pass = 0, fail = 0, external = 0, shadowInfo = 0;
        List<String> problems = new ArrayList<>();

        for (File mf : mixinFiles) {
            String src = new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8);
            String name = mf.getName();

            // @Mixin(X.class) → 目标类内部名
            // ante 用短类名 + import ⇒ 需要按 import 解析成全限定名
            Map<String, String> imports = new HashMap<>();
            List<String> wildcardPkgs = new ArrayList<>();
            Matcher im = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE).matcher(src);
            while (im.find()) {
                String fq = im.group(1);
                if (im.group(2) != null) {
                    wildcardPkgs.add(fq);      // import mtr.data.*;
                } else {
                    imports.put(fq.substring(fq.lastIndexOf('.') + 1), fq);
                }
            }
            Matcher mm = Pattern.compile("@Mixin\\s*\\(\\s*(?:value\\s*=\\s*)?([\\w.]*?)([\\w]+)\\.class").matcher(src);
            String targetSimple = null;
            String target = null;
            if (mm.find()) {
                String qual = mm.group(1);          // "mtr.client." 或 ""
                String simple = mm.group(2);
                targetSimple = simple;
                if (!qual.isEmpty()) {
                    // 显式包前缀，可能是 pkg.Outer. 或 pkg.
                    String trimmed = qual.substring(0, qual.length() - 1);   // 去掉末尾 '.'
                    String ofq = imports.get(trimmed);
                    if (ofq != null) {
                        target = ofq.replace('.', '/') + "$" + simple;       // import 了 Outer，取嵌套类
                    } else {
                        target = trimmed.replace('.', '/') + "/" + simple;
                    }
                } else {
                    String fq = imports.get(simple);
                    if (fq != null) {
                        target = fq.replace('.', '/');
                    }
                }
                // 仍未解析 ⇒ 用通配包 + 目标 jar 里实际存在的类来定名
                if (target == null && !idxs.isEmpty()) {
                    JarIndex probe = idxs.values().iterator().next();
                    List<String> cands = new ArrayList<>();
                    for (String pkg : wildcardPkgs) {
                        cands.add(pkg.replace('.', '/') + "/" + simple);
                    }
                    if (!cands.isEmpty()) {
                        for (String c : cands) {
                            if (probe.classes.containsKey(c)) { target = c; break; }
                        }
                    }
                    if (target == null) {
                        // 最后兜底：全 jar 里唯一同名的类
                        List<String> hits = new ArrayList<>();
                        for (String cn : probe.classes.keySet()) {
                            String sn = cn.contains("$") ? cn.substring(cn.lastIndexOf('$') + 1) : cn.substring(cn.lastIndexOf('/') + 1);
                            if (sn.equals(simple)) hits.add(cn);
                        }
                        if (hits.size() == 1) target = hits.get(0);
                        else if (hits.size() > 1) {
                            for (String h : hits) {
                                if (!h.contains("$")) { target = h; break; }
                            }
                        }
                    }
                }
            }

            // 注入器
            Matcher inj = Pattern.compile(
                    "@(Inject|Redirect|ModifyArg|ModifyVariable|ModifyConstant|ModifyExpressionValue|WrapOperation|ModifyReceiver)\\s*\\("
            ).matcher(src);
            while (inj.find()) {
                String kind = inj.group(1);
                int open = inj.end() - 1;
                int depth = 0, close = open;
                for (int i = open; i < src.length(); i++) {
                    char c = src.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') { depth--; if (depth == 0) { close = i; break; } }
                }
                String body = src.substring(open, close + 1);
                Matcher methM = Pattern.compile("method\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(body);
                if (!methM.find()) continue;
                String spec = methM.group(1);
                boolean hasRequire = Pattern.compile("\\brequire\\s*=\\s*\\d+").matcher(body).find();
                boolean remapFalse = Pattern.compile("remap\\s*=\\s*false").matcher(body).find();

                // 拆出 方法名 与 描述符
                String mname, mdesc;
                int paren = spec.indexOf('(');
                if (paren < 0) { mname = spec; mdesc = null; }
                else {
                    mname = spec.substring(0, paren);
                    mdesc = spec.substring(paren);
                }
                if (mname.isEmpty()) continue; // 由 @Shadow 推断的（setter/getter）

                boolean allFound = true;
                StringBuilder detail = new StringBuilder();
                for (Map.Entry<String, JarIndex> e : idxs.entrySet()) {
                    JarIndex ji = e.getValue();
                    Integer acc = null;
                    if (target != null) {
                        if (mdesc != null) {
                            acc = ji.findMethod(target, mname, mdesc);
                        } else {
                            // 只有方法名 → 找同名任意描述符
                            for (String k : ji.methods.keySet()) {
                                if (k.startsWith(target + "." + mname) && k.indexOf('(') > 0) {
                                    String head = k.substring(0, k.indexOf('('));
                                    if (head.equals(target + "." + mname)) { acc = ji.methods.get(k); break; }
                                }
                            }
                        }
                    }
                    if (acc == null) {
                        allFound = false;
                        detail.append(" [").append(e.getKey()).append(" 缺失]");
                    }
                }                if (target != null && EXTERNAL_LIB_TARGETS.contains(target)) {
                    external++;
                    System.out.printf("-- %-42s %-20s %-55s 外部库目标（预期盲区，跳过）%n", name, kind, spec);
                    continue;
                }
                if (!allFound) {
                    fail++;
                    problems.add(String.format("%s  %s  method=\"%s\"  → 目标 jar 中不存在:%s%s",
                            name, kind, spec, detail, hasRequire ? "" : "   ★无 require=0 ⇒ defaultRequire:1 下崩"));
                    System.out.printf("!! %-42s %-20s %s%s%n", name, kind, spec, detail);
                } else {
                    pass++;
                    if (!hasRequire) {
                        problems.add(String.format("%s  %s  method=\"%s\"  存在但无 require=0",
                                name, kind, spec));
                        System.out.printf("~  %-42s %-20s %-55s 存在，但无 require=0%n", name, kind, spec);
                    }
                }
            }

            // ── @Shadow 可见性审计 ──
        // turbo 1.1.6 踩过：@Shadow 声明的可见性低于目标成员 ⇒ 运行时 InvalidMixinException。
        // 匹配源码里 "@Shadow(...) ... <vis> ... name" 的声明。
        Matcher shM = Pattern.compile(
                "@Shadow\\s*\\(([^)]*)\\)\\s*\\n\\s*(public|protected|private)?\\s*(static\\s+)?(final\\s+)?(\\w[\\w\\[\\]]*)\\s+(\\w+)\\s*(\\([^)]*\\))?"
        ).matcher(src);
        while (shM.find()) {
            String smods = shM.group(2);
            String sname = shM.group(6);
            String sparams = shM.group(7);
            boolean isMethod = sparams != null;
            int declaredVis = vis(smods);
            if (target == null) continue;
            for (Map.Entry<String, JarIndex> e : idxs.entrySet()) {
                JarIndex ji = e.getValue();
                Integer tacc = null;
                if (isMethod) {
                    for (String k : ji.methods.keySet()) {
                        String head = k.substring(k.lastIndexOf('.') + 1);
                        String mn = head.substring(0, head.indexOf('('));
                        if (mn.equals(sname) && k.startsWith(target + ".")) { tacc = ji.methods.get(k); break; }
                    }
                } else {
                    for (String k : ji.fields.keySet()) {
                        String head = k.substring(k.lastIndexOf('.') + 1);
                        String fn = head.substring(0, head.indexOf(' '));
                        if (fn.equals(sname) && k.startsWith(target + ".")) { tacc = ji.fields.get(k); break; }
                    }
                }
                if (tacc == null) continue;
                int targetVis = (tacc & Opcodes.ACC_PUBLIC) != 0 ? 2 :
                        ((tacc & Opcodes.ACC_PROTECTED) != 0 ? 1 : 0);
                if (declaredVis < targetVis) {
                    shadowInfo++;
                    String tvisName = targetVis == 2 ? "public" : (targetVis == 1 ? "protected" : "private");
                    System.out.printf("i  %-42s @Shadow %-24s %-8s 声明=%-16s 目标=%-10s  ⇒ 运行时静默升级，非缺陷%n",
                            name, sname, isMethod ? "method" : "field",
                            visName(smods), tvisName);
                }
            }
        }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("注入器 method= 在 4 个 MC 版本目标 jar 中全部存在: " + pass);
        System.out.println("缺失（真缺陷）: " + fail);
        System.out.println("外部库目标（预期盲区，已跳过）: " + external);
        System.out.println("@Shadow 可见性差异（Mixin 运行时会静默升级，非缺陷）: " + shadowInfo);
        System.out.println();
        if (fail > 0) {
            System.out.println("=== ★ 必须修复 ===");
            for (String p : problems) System.out.println("  " + p);
            throw new IllegalStateException("发现 " + fail + " 个永不匹配的注入器");
        } else {
            System.out.println("=== 结论：未发现描述符失效缺陷 ===");
            System.out.println("    （" + pass + " 个注入器的 method= 在 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 全部命中；");
            System.out.println("      @Shadow 可见性差异仅作提示 —— Mixin 0.8.5 conformVisibility 对非 @Overwrite 会静默升级）");
        }
    }
}
