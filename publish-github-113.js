const fs = require('fs');
const https = require('https');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const repo = 'szkele1145/mtr-ante-continue';
const VER = '1.1.1-continue.3';
const tag = 'v' + VER;

const assets = ['1.20.1', '1.19.4', '1.19.2', '1.18.2'].map(mc =>
  'F:\\vibe coding工程\\mtr-ante\\build\\MTR-ANTE-' + VER + '+' + mc + '.jar'
).filter(p => fs.existsSync(p));

const notes = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '代码审查报告修复（6 项）+ 构建环境修复。',
  '',
  '| MC | Jar (Fabric+Forge 合并) |',
  '|---|---|',
  '| 1.20.1 | MTR-ANTE-' + VER + '+1.20.1.jar |',
  '| 1.19.4 | MTR-ANTE-' + VER + '+1.19.4.jar |',
  '| 1.19.2 | MTR-ANTE-' + VER + '+1.19.2.jar |',
  '| 1.18.2 | MTR-ANTE-' + VER + '+1.18.2.jar |',
  '',
  '### 🔴 高危：资源包脚本配置注入双重转义（#7）',
  '',
  '旧实现 `Gson.toJson` → 手工 `replace("\\"", "\\\\\\"")` → 拼进 JS **模板字符串**。',
  '模板字符串会**再解释一次转义序列**，配置含 Windows 路径（`C:\\mc\\server`）、',
  '换行或制表符时 `JSON.parse` 抛 `Bad escaped character` / `Bad control character`，',
  '整个资源包 `load()` 失败。',
  '',
  '现改为 JSON 文本经 `globalBindings.putMember` 传入（**只编码一次**），',
  'JS 侧 `JSON.parse` 出对象。脚本侧 `CONFIG_INFO.xxx` 用法不变。',
  '',
  '### 中危',
  '',
  '- 轨道区块 `boundingBox` 改 `volatile`（后台线程写 / 渲染线程读，原无 happens-before）',
  '- `finishCallback` 移入 `finally`（原异常时不执行 ⇒ 每帧无限重试 create）',
  '- 删除 `if (false)` 死代码块（约 120 行，内含全沙箱打开配置）',
  '',
  '### 低危',
  '',
  '- 脚本沙箱 root 也做 `toRealPath()`（含符号链接时不再误判越界）',
  '- `resetRunner` 加 `awaitTermination(1s)`（消除与 `close()` 的竞态）',
  '',
  '### 构建环境',
  '',
  '- `java.home` `Z:/jdk17` → `C:/jdk17`（`Z:` 只由别的工程临时挂载）',
  '- `gradle.properties` 加代理（直连 piston-data 被 TLS 中间盒截断）',
  '- `build.ps1` 修硬编码路径',
  '',
  '### 验证',
  '',
  '- 四个 MC 版本 `gradle build` 全部 BUILD SUCCESSFUL',
  '- 字节码核对：fabric/forge 两端 `ScriptHolderBase.class` 均含',
  '  `putMember("CONFIG_INFO_JSON", ...)` + `JSON.parse(CONFIG_INFO_JSON)`，',
  '  模板字符串形式已消失，死代码符号已不在 class 中。',
  '',
  '### 未修（诚实标注）',
  '',
  '- 脚本无执行超时（#9）：需要 GraalVM 层的超时控制，改动面较大。'
].join('\n');

function api(method, path, bodyObj) {
  return new Promise((resolve, reject) => {
    const data = bodyObj ? Buffer.from(JSON.stringify(bodyObj), 'utf8') : null;
    const req = https.request({
      hostname: 'api.github.com', port: 443, method, path,
      headers: {
        'Authorization': 'token ' + tok, 'User-Agent': 'szkele1145',
        'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
        'Content-Length': data ? data.length : 0
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        let p = null; try { p = JSON.parse(raw); } catch (e) { p = raw; }
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(p);
        else reject(new Error('HTTP ' + res.statusCode + ' ' + String(raw).slice(0, 300)));
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

function uploadAsset(rel, filePath) {
  const buf = fs.readFileSync(filePath);
  const name = filePath.split('\\').pop();
  const url = rel.upload_url.replace(/\{.*\}$/, '') + '?name=' + encodeURIComponent(name);
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request({
      hostname: u.hostname, port: 443, method: 'POST', path: u.pathname + u.search,
      headers: {
        'Authorization': 'token ' + tok, 'User-Agent': 'szkele1145',
        'Content-Type': 'application/octet-stream', 'Content-Length': buf.length
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          const j = JSON.parse(raw);
          console.log('UPLOAD_OK ' + j.name + ' size=' + j.size + ' id=' + j.id);
          resolve();
        } else reject(new Error('HTTP ' + res.statusCode + ' ' + raw.slice(0, 200)));
      });
    });
    req.on('error', reject);
    req.write(buf);
    req.end();
  });
}

(async () => {
  const sha = process.argv[2];
  if (!sha) { console.error('用法: node publish-github-113.js <commit-sha>'); process.exit(1); }
  console.log('assets=' + assets.length);
  for (const a of assets) if (!fs.existsSync(a)) { console.error('缺: ' + a); process.exit(1); }

  try { await api('GET', '/repos/' + repo + '/git/ref/tags/' + tag); console.log('TAG_EXISTS'); }
  catch (e) {
    const r = await api('POST', '/repos/' + repo + '/git/refs', { ref: 'refs/tags/' + tag, sha });
    console.log('TAG_CREATED ' + r.ref);
  }

  let rel;
  try { rel = await api('GET', '/repos/' + repo + '/releases/tags/' + tag); console.log('RELEASE_EXISTS id=' + rel.id); }
  catch (e) {
    rel = await api('POST', '/repos/' + repo + '/releases', {
      tag_name: tag, name: 'MTR-ANTE ' + VER + ' - 审查报告 6 项修复',
      body: notes, draft: false, prerelease: false
    });
    console.log('RELEASE_CREATED id=' + rel.id + ' url=' + rel.html_url);
  }

  for (const a of assets) {
    const name = a.split('\\').pop();
    if ((rel.assets || []).find(x => x.name === name)) { console.log('SKIP ' + name); continue; }
    await uploadAsset(rel, a);
  }
  console.log('DONE');
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
