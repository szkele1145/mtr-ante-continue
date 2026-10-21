// MTR-ANTE Continued 1.1.1-continue.5 —— GitHub Release 发布脚本
// 用法：先 git push，再 node publish-github-115.js <commit-sha-40hex>
// （tag 必须指向已推送的 commit，否则 422）
const fs = require('fs');
const https = require('https');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const repo = 'szkele1145/mtr-ante-continue';
const tag = 'v1.1.1-continue.5';

const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-115\\';
const VER = '1.1.1-continue.5';
const assets = [
  '1.20.1', '1.19.4', '1.19.2', '1.18.2'
].map(mc => STAGE + 'MTR-ANTE-' + VER + '+' + mc + '.jar').filter(p => fs.existsSync(p));

const notes = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '**看门狗线程爆炸修复（性能优化版）。**',
  '',
  '### 🟡 JS 脚本超时看门狗的线程爆炸',
  '',
  '**问题**：continue.4 引入的 10 秒脚本超时看门狗，每次脚本调用都会新建 **2 个**',
  '一次性线程（watchdog 轮询 + watcher 等待）。而 `tryCallRenderFunctionAsync` 是',
  '**每帧每脚本**调用一次 —— 60 FPS 下每个脚本上下文每秒创建 **120** 个线程对象。',
  '资源包脚本一多（多列车 / 多景物各一个 context），短时间内堆积数千个线程，',
  '栈内存与 GC 压力显著，高负载下拖垮服务端。',
  '',
  '**修复**：',
  '',
  '- 单一静态 `ScheduledExecutorService`（1 个 daemon 线程）调度看门狗；',
  '- 每次调用只 **schedule** 一个延迟 10s 的一次性任务，不新建线程；',
  '- 任务触发时用 `task.isDone()` 判断脚本是否已提前完成：',
  '  已完成 => 直接返回（取代原 watcher 线程）；未完成 => `context.interrupt(Duration)`。',
  '',
  '**超时语义完全不变**：10s 超时 / 1s 宽限 / `AtomicBoolean` 只中断一次。',
  '',
  '### 验证（PASS=14 FAIL=0）',
  '',
  '| # | 事实 | 结果 |',
  '|---|---|---|',
  '| 6 | 提前完成的脚本不会被中断（`isDone` 短路），context 仍可用 | ok |',
  '| 7 | 超时脚本被中断并退出，抛 `PolyglotException` | ok |',
  '| 8 | **连续 1000 次调度后看门狗线程数仍为 1**（未线程爆炸） | ok |',
  '',
  '完整 14 项见 Modrinth 更新日志。',
  '',
  '### 版本矩阵',
  '',
  'MC 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1，每版均为 Fabric + Forge 合并包。',
  '',
  '### 附件',
  '',
  ...assets.map(a => '- ' + a.split('\\').pop()),
  '',
  '需要 MTR（Minecraft Transit Railway）3.1.12+。'
].join('\n');

function api(method, path, bodyObj) {
  return new Promise((resolve, reject) => {
    const data = bodyObj ? Buffer.from(JSON.stringify(bodyObj), 'utf8') : null;
    const req = https.request({
      hostname: 'api.github.com', port: 443, method, path,
      headers: {
        'Authorization': 'token ' + tok,
        'User-Agent': 'szkele1145',
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'Content-Length': data ? data.length : 0
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        let parsed = null;
        try { parsed = JSON.parse(raw); } catch (e) { parsed = raw; }
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
        else reject(new Error('HTTP ' + res.statusCode + ' ' + String(raw).slice(0, 300)));
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

function uploadAsset(rel, filePath) {
  const fileBuf = fs.readFileSync(filePath);
  const fileName = filePath.split('\\').pop();
  const uploadUrl = rel.upload_url.replace(/\{.*\}$/, '') + '?name=' + encodeURIComponent(fileName);
  return new Promise((resolve, reject) => {
    const u = new URL(uploadUrl);
    const req = https.request({
      hostname: u.hostname, port: 443, method: 'POST', path: u.pathname + u.search,
      headers: {
        'Authorization': 'token ' + tok,
        'User-Agent': 'szkele1145',
        'Content-Type': 'application/octet-stream',
        'Content-Length': fileBuf.length
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          const j = JSON.parse(raw);
          console.log('UPLOAD_OK ' + j.name + ' size=' + j.size + ' id=' + j.id);
          resolve(j);
        } else reject(new Error('HTTP ' + res.statusCode + ' ' + raw.slice(0, 200)));
      });
    });
    req.on('error', reject);
    req.write(fileBuf);
    req.end();
  });
}

(async () => {
  const sha = process.argv[2];
  if (!sha) { console.error('用法: node publish-github-115.js <commit-sha>'); process.exit(1); }
  console.log('assets=' + assets.length + ' sha=' + sha);
  if (assets.length === 0) { console.error('暂存目录无产物'); process.exit(1); }

  try {
    await api('GET', '/repos/' + repo + '/git/ref/tags/' + tag);
    console.log('TAG_EXISTS');
  } catch (e) {
    const r = await api('POST', '/repos/' + repo + '/git/refs', { ref: 'refs/tags/' + tag, sha });
    console.log('TAG_CREATED ' + r.ref + ' -> ' + r.object.sha);
  }

  let rel;
  try {
    rel = await api('GET', '/repos/' + repo + '/releases/tags/' + tag);
    console.log('RELEASE_EXISTS id=' + rel.id);
  } catch (e) {
    rel = await api('POST', '/repos/' + repo + '/releases', {
      tag_name: tag, name: 'MTR-ANTE ' + VER + ' - 看门狗线程爆炸修复',
      body: notes, draft: false, prerelease: false
    });
    console.log('RELEASE_CREATED id=' + rel.id + ' url=' + rel.html_url);
  }

  for (const a of assets) {
    const name = a.split('\\').pop();
    const existing = (rel.assets || []).find(x => x.name === name);
    if (existing) {
      console.log('SKIP（已存在）: ' + name + ' size=' + existing.size);
      continue;
    }
    const up = await uploadAsset(rel, a);
    const local = fs.statSync(a).size;
    console.log('  verify: local=' + local + ' remote=' + up.size + ' ' + (local === up.size ? 'MATCH' : 'MISMATCH!'));
  }
  console.log('DONE');
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
