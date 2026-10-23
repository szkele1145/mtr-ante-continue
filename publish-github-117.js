// MTR-ANTE Continued 1.1.1-continue.7 —— GitHub Release 发布脚本
// 用法：先 git push，再 node publish-github-117.js <commit-sha-40hex>
// （tag 必须指向已推送的 commit，否则 422）
const fs = require('fs');
const https = require('https');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const repo = 'szkele1145/mtr-ante-continue';
const tag = 'v1.1.1-continue.7';

const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-117\\';
const VER = '1.1.1-continue.7';
const assets = [
  '1.20.1', '1.19.4', '1.19.2', '1.18.2'
].map(mc => STAGE + 'MTR-ANTE-' + VER + '+' + mc + '.jar').filter(p => fs.existsSync(p));

const notes = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '**脚本执行预算自适应：把「慢脚本」和「死循环」区分开**，并修掉一处回归测试偶发假失败。',
  '',
  '### 1. 脚本执行预算自适应',
  '',
  '**问题**：看门狗是**固定 10 秒**墙钟超时。一个合法但重的脚本（例如首次构建几何体要 12 秒）',
  '会和死循环一样被砍掉，而且日志里两者长得一模一样，作者无法判断到底是自己写慢了还是写错了。',
  '',
  '**判据**（关键设计）：只有**已经成功完成过**的脚本才允许超过基础预算。',
  '',
  '| 情形 | 预算 | 理由 |',
  '|---|---|---|',
  '| 从未成功完成（`successfulRuns < 2`） | 恒定 **10 s** | 死循环第一次就爆，不会被「自适应」放生 |',
  '| 已有成功历史 | `clamp(4 × 历史最长成功耗时, 10 s, 60 s)` | 慢脚本按自己的历史水平拿到余量 |',
  '',
  '只有「成功完成」的那次计入历史：失败/被中断的耗时若也计入，',
  '死循环会把自己的超时时长当成「历史最长耗时」，从而不断抬高自己的预算 —— **自我放生**。',
  '',
  '为什么乘 4 而不是乘 2：render 回调耗时抖动很大（GC、区块加载、首次 JIT），',
  '2 倍容易反复误杀；4 倍仍把「慢」限制在一个数量级内。',
  '为什么上限 60 s：再长的单次调用已经不是「慢」而是设计问题，',
  '且脚本线程是**单线程**（一个脚本卡住会拖住所有脚本）。',
  '',
  '**日志现在一眼能分辨**：',
  '',
  '```',
  '# 慢脚本',
  'ANTE script is slow: exceeded its adaptive budget of 20000 ms',
  '  (base 10000 ms, 5 successful runs, longest successful run 5000 ms)',
  '',
  '# 疑似死循环',
  'ANTE script timed out after 10000 ms with no successful run on record',
  '  (0 completed run(s) so far) - likely an infinite loop',
  '```',
  '',
  '### 2. 新增 `ScriptBudgetPolicy`（纯函数、零依赖）',
  '',
  '预算算法单独成类，**不引用 MC / GraalVM**。',
  '',
  '之所以要抽出来：`ScriptHolderBase` 依赖 MC 与 GraalVM，离屏无法加载，',
  '而「预算怎么算」恰恰最需要回归（算错会导致合法慢脚本被误杀、或死循环被放生）。',
  '抽出来后 `tools/VerifyScriptTimeout.java` 直接调用**生产代码**，',
  '不复制参考实现 —— 避免测试与实现悄悄分叉。',
  '',
  '### 3. 修掉一个偶发假失败（continue.6 起就存在）',
  '',
  '`VerifyScriptTimeout` 的事实 2/3/4 原先靠 `started2.countDown()` + 固定 `Thread.sleep(500)`',
  '来「等脚本跑起来」。机器负载高时（gradle daemon + 后台构建）victim 线程可能还没进入',
  'polyglot 执行体，`interrupt` 就找不到可中断的目标，或 1 秒等待窗口在排队中耗尽',
  '⇒ **5 条断言随机失败**（实测遇到过一次）。',
  '',
  '改为**由 JS 自己回报**：脚本进入循环前调用宿主回调 `__enterLoop()`，',
  '主线程 `await` 这个 latch 再 interrupt —— 何时可以中断由事实决定，',
  '不再由「猜一个睡够的时长」决定。',
  '',
  '### 验证',
  '',
  '`verifyScriptTimeout` **PASS=26 FAIL=0**（continue.6 = 14，+12），连跑 4 次均稳定。',
  '',
  '新增断言涵盖：无成功历史恒基础预算（含 0 次、1 次、无耗时样本、负数样本）、',
  '4 倍放大、下限抬升、上限夹取，以及两条不变式（预算恒在 [10 s, 60 s]、',
  '耗时样本越大预算不减）。',
  '',
  '### 版本矩阵',
  '',
  'MC 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1，每个版本都是 Fabric + Forge 合并包。',
  '',
].join('\n');

function api(method, path, data) {
  return new Promise((resolve, reject) => {
    const body = data ? JSON.stringify(data) : null;
    const req = https.request({
      hostname: 'api.github.com', port: 443, method, path,
      headers: Object.assign({
        'Authorization': 'token ' + tok,
        'User-Agent': 'szkele1145',
        'Accept': 'application/vnd.github+json'
      }, body ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } : {})
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(raw ? JSON.parse(raw) : {});
        else reject(new Error('HTTP ' + res.statusCode + ' ' + String(raw).slice(0, 300)));
      });
    });
    req.on('error', reject);
    if (body) req.write(body);
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
  if (!sha) { console.error('用法: node publish-github-117.js <commit-sha>'); process.exit(1); }
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
      tag_name: tag, name: 'MTR-ANTE ' + VER + ' - 脚本执行预算自适应（区分慢脚本与死循环）',
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
