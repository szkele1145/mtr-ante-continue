// MTR-ANTE Continued 1.1.1-continue.6 —— GitHub Release 发布脚本
// 用法：先 git push，再 node publish-github-115.js <commit-sha-40hex>
// （tag 必须指向已推送的 commit，否则 422）
const fs = require('fs');
const https = require('https');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const repo = 'szkele1145/mtr-ante-continue';
const tag = 'v1.1.1-continue.6';

const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-116\\';
const VER = '1.1.1-continue.6';
const assets = [
  '1.20.1', '1.19.4', '1.19.2', '1.18.2'
].map(mc => STAGE + 'MTR-ANTE-' + VER + '+' + mc + '.jar').filter(p => fs.existsSync(p));

const notes = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '**滚转排序缓存的并发可见性与数组配对缺陷修复。**',
  '',
  '### 🔴 滚转缓存的双字段竞态（AIOOBE / NPE 崩溃）',
  '',
  '`RailMixin` 缓存了滚转角度的排序键与排序值，供 `RailExtraSupplier.getRollAngle()`',
  '每帧二分查询使用（多车多线存档下每帧上千次调用）。',
  '',
  '**旧实现的问题**：缓存是**两个独立字段**，且**读写顺序相反**：',
  '',
  '| | 顺序 |',
  '|---|---|',
  '| 写端 `nteRebuildRollCache()` | 先赋 `values` → 再赋 `keys` |',
  '| 读端 `getRollAngle()` | 先取 `keys` → 再取 `values` |',
  '',
  '⇒ 渲染线程可能观察到「**新 keys + 旧 values**」两个数组，长度不一致',
  '⇒ `values[idx]` **越界（AIOOBE）**。',
  '',
  '此外两字段都**没有 `volatile`**：编辑线程的失效写入对渲染线程**不可见**',
  '（无 happens-before）⇒ 滚转数据可能不更新；且 `nte$getSortedRollKeys()` 在',
  '`invalidate` 与 `rebuild` 之间**可能返回 null** ⇒ `getRollAngle` 里 `keys.length` **NPE**。',
  '',
  '**修复**：把两个数组放进**不可变 holder**（`NteRollCache`），用**单个 `volatile` 字段**发布：',
  '',
  '- `volatile` 写 = happens-before ⇒ **可见性**有保证；',
  '- 单字段发布 ⇒ keys 与 values **必然来自同一次重建、长度必然一致**。',
  '',
  '三处失效点（`setRollAngleMap` / messagepack 反序列化 / 网络包反序列化）简化为置一个字段 null。',
  '',
  '### 验证（PASS=8 FAIL=0）',
  '',
  '新增 `tools/VerifyRollCacheVisibility.java`（独立 Java 程序，不依赖 MC / GraalVM），',
  '接入 gradle 任务 `verifyRollCacheVisibility`。压力测试（写线程持续重建 +',
  '读线程按**生产代码的顺序**取用并索引）：',
  '',
  '| # | 事实 | 结果 |',
  '|---|---|---|',
  '| 1 | 旧的双字段实现能观察到「长度不一致的数组对」 | ok |',
  '| 2 | 旧实现能复现 **AIOOBE**（`Index 2 out of bounds for length 2`） | ok |',
  '| 3 | 新的 holder 实现**永远配对** | ok |',
  '| 4 | 新的 holder 实现**无越界异常** | ok |',
  '| 5 | holder 失效后能读到新数据（可见性语义正确） | ok |',
  '',
  '第 2 项实测到了**真实的崩溃场景**：新 keys 长度 3 + 旧 values 长度 2。',
  '',
  '### 上一版回顾',
  '',
  'continue.5 修掉 JS 脚本超时看门狗的线程爆炸（每帧每脚本 120 线程/秒 → 恒 1 线程）；',
  'continue.4 加上了 10 秒脚本超时；continue.3 修了审查报告 #7 / #8 / #10 / #11 / #13 / #14。',
  '审查报告 7 项**全部闭合**。',
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
      tag_name: tag, name: 'MTR-ANTE ' + VER + ' - 滚转缓存并发缺陷修复（AIOOBE/NPE）',
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
