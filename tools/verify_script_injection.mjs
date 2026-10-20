// 精确验证 mtr-ante ScriptHolderBase.appendImporter() 的 CONFIG_INFO 注入
//
// 上游代码（common/src/main/java/cn/zbx1425/mtrsteamloco/scripting/ScriptHolderBase.java:346-352）：
//   String configInfo = new GsonBuilder().disableHtmlEscaping().create().toJson(config);
//   configInfo = configInfo.replace("\"", "\\\"");
//   eval("CONFIG_INFO = JSON.parse(`" + configInfo + "`);");
//
// 关键：模板字符串（反引号）本身有转义规则，\\ -> \，\n -> 真实换行。
// 而 Gson.toJson 已经把特殊字符编码为 JSON 转义序列。
// 于是 JSON 的转义序列被模板字符串**二次解释**，原始数据失真。
const cases = [
  { name: "含反斜杠(Windows 路径)", cfg: { path: "C:\\mc\\server" } },
  { name: "含换行", cfg: { desc: "line1\nline2" } },
  { name: "含制表符", cfg: { desc: "a\tb" } },
  { name: "含中文", cfg: { name: "地铁列车" } },
  { name: "普通", cfg: { name: "ok" } },
];

for (const c of cases) {
  const configInfo = JSON.stringify(c.cfg);          // Gson.toJson 等价
  const escaped = configInfo.replace(/"/g, '\\"');   // 上游的 replace

  // 1) 模板字符串求值（模拟 eval 里的 `...`）
  let literalValue;
  try {
    literalValue = eval("`" + escaped + "`");
  } catch (e) {
    console.log(`[${c.name}] 模板字面量本身解析失败: ${e.message}`);
    continue;
  }

  // 2) 对模板结果做 JSON.parse（模拟脚本运行）
  let parsed, err = null;
  try { parsed = JSON.parse(literalValue); } catch (e) { err = e.message; }

  const orig = c.cfg[c.name.startsWith("含中文") ? "name" : Object.keys(c.cfg)[0]];
  const got = parsed ? parsed[Object.keys(c.cfg)[0]] : null;
  const lost = !err && got !== orig;

  console.log(`[${c.name}]`);
  console.log(`  原始 Java 值      : ${JSON.stringify(orig)}`);
  console.log(`  Gson.toJson       : ${configInfo}`);
  console.log(`  replace 后        : ${escaped}`);
  console.log(`  模板求值后字符串  : ${JSON.stringify(literalValue)}`);
  if (err) {
    console.log(`  ❌ JSON.parse 失败 : ${err}`);
  } else if (lost) {
    console.log(`  ❌ 数据失真        : 期望 ${JSON.stringify(orig)}，实得 ${JSON.stringify(got)}`);
  } else {
    console.log(`  ✅ 解析正常        : ${JSON.stringify(got)}`);
  }
}
