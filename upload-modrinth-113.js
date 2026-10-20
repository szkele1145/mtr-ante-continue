const fs = require('fs');
const https = require('https');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();

const VER = '1.1.1-continue.3';
const builds = [
  { mc: '1.20.1' }, { mc: '1.19.4' }, { mc: '1.19.2' }, { mc: '1.18.2' },
].map(b => ({
  version_number: VER + '+' + b.mc,
  jar: 'F:\\vibe coding工程\\mtr-ante\\build\\MTR-ANTE-' + VER + '+' + b.mc + '.jar',
  name: 'MTR-ANTE ' + VER + ' (MC ' + b.mc + ', Fabric+Forge)',
  game_versions: [b.mc], loaders: ['fabric', 'forge'],
}));

const changelog = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '代码审查报告修复（6 项）+ 构建环境修复。',
  '',
  '### 🔴 高危：资源包脚本配置注入双重转义（#7）',
  '',
  '旧实现：`Gson.toJson(config)` → 手工 `replace("\\"", "\\\\\\"")` →',
  '拼进 JS **模板字符串** → `JSON.parse(\\`...\\`)`。',
  '',
  '模板字符串会**再解释一次转义序列**，所以：',
  '',
  '- 配置值含 Windows 路径（`C:\\mc\\server`）→ `JSON.parse` 抛 `Bad escaped character`',
  '- 配置值含换行/制表符 → `Bad control character`',
  '- ⇒ 整个资源包的 `load()` 失败，脚本完全不工作，且报错指向 JSON 而非「转义」',
  '',
  '现改为 JSON 文本经 `globalBindings.putMember` 传入（**只编码一次**，',
  'GraalVM 直接搬运字符串，不经任何 eval 拼接），JS 侧 `JSON.parse` 出对象。',
  '脚本侧 `CONFIG_INFO.xxx` 用法**不变**。',
  '',
  '`inject(String, String)` 同样从 eval 拼接改走 bindings（消除同类注入面）。',
  '',
  '### 中危',
  '',
  '| 项 | 修法 |',
  '|---|---|',
  '| 轨道区块 `boundingBox` 跨线程无可见性（#8） | 后台线程写、渲染线程读，普通字段无 happens-before ⇒ 可能读到半更新 AABB（视锥剔除错乱）。改 `volatile` |',
  '| `finishCallback` 异常时不执行（#10） | `scriptCtx.created` 永为 false ⇒ **每帧**走 create 分支无限重试；`renderFunctionFinished()` 永不调用。移入 `finally` |',
  '| 死代码块含全沙箱配置（#11） | `if (false) { ... }` 约 120 行，内含 `allowCreateProcess` / `allowHostClassLookup`（全放行）/ `allowIO(ALL)` / `allowNativeAccess(true)`。整块删除 |',
  '',
  '### 低危',
  '',
  '| 项 | 修法 |',
  '|---|---|',
  '| 脚本沙箱 root 未解引用符号链接（#13） | root 含符号链接时合法路径被误判越界。root 也做 `toRealPath()` |',
  '| `resetRunner` 不等旧线程（#14） | `shutdownNow()` 后立刻换 executor，旧线程可能仍持有 Context，与 `close()` 竞态。加 `awaitTermination(1s)` |',
  '',
  '### 构建环境',
  '',
  '- `gradle.properties`：`java.home` `Z:/jdk17` → `C:/jdk17`',
  '  （`Z:` 只由 mtr-next 的构建脚本临时挂载并立即删除，本工程独立构建必然失败）',
  '- `gradle.properties` 加代理：直连 `piston-data.mojang.com` 被 TLS 中间盒截断',
  '  （`bad_record_mac` Tag mismatch，收 5.7/46.6 MB 即断）',
  '- `build.ps1`：硬编码 `F:\\mtr-ante\\gradlew.bat`（工程不在那）→ `$PSScriptRoot` + `V:` subst',
  '',
  '### 验证',
  '',
  '- `gradle build` 四个 MC 版本全部 BUILD SUCCESSFUL。',
  '- 字节码核对：fabric 与 forge 两端 `ScriptHolderBase.class` 均含',
  '  `putMember("CONFIG_INFO_JSON", ...)` + `JSON.parse(CONFIG_INFO_JSON)`，',
  '  且模板字符串形式已消失；死代码符号已不在 class 中。',
  '',
  '### 未修（诚实标注）',
  '',
  '- 脚本无执行超时（#9）：需要 GraalVM 层的超时控制，改动面较大。',
].join('\n');

function upload(b) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(b.jar)) return reject(new Error('缺文件: ' + b.jar));
    const fileBuf = fs.readFileSync(b.jar);
    const fileName = b.jar.split('\\').pop();
    const data = {
      name: b.name, version_number: b.version_number,
      game_versions: b.game_versions, loaders: b.loaders,
      featured: false, dependencies: [], changelog,
      release_channel: 'release',
      project_id: 's3V6fHtn', file_parts: [fileName]
    };
    const boundary = '----DSHB' + Date.now() + Math.random().toString(36).slice(2);
    const chunks = [];
    chunks.push(Buffer.from('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n', 'utf8'));
    chunks.push(Buffer.from('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + fileName + '"\r\nContent-Type: application/java-archive\r\n\r\n', 'utf8'));
    chunks.push(fileBuf);
    chunks.push(Buffer.from('\r\n--' + boundary + '--\r\n', 'utf8'));
    const body = Buffer.concat(chunks);
    const req = https.request({
      hostname: 'api.modrinth.com', port: 443, method: 'POST', path: '/v2/version',
      headers: {
        'Authorization': mr,
        'User-Agent': 'szkele1145/mtr-ante-continue/' + VER,
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
        'Content-Length': body.length
      }
    }, res => {
      let out = '';
      res.on('data', c => out += c);
      res.on('end', () => {
        console.log(b.version_number + ' -> ' + res.statusCode);
        if (res.statusCode === 200) {
          const j = JSON.parse(out);
          console.log('  id=' + j.id + ' size=' + j.files[0].size);
          resolve();
        } else { console.log(out.slice(0, 400)); reject(new Error('HTTP ' + res.statusCode)); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

(async () => {
  for (const b of builds) await upload(b);
  console.log('ALL DONE');
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
