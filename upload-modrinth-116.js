// MTR-ANTE Continued 1.1.1-continue.6 —— Modrinth 上传脚本
//
// 本版内容：滚转排序缓存的并发可见性与数组配对缺陷修复（RailMixin）。
// 旧实现把排序缓存拆成两个独立字段，且写端（values→keys）与读端（keys→values）
// 顺序相反 ⇒ 渲染线程可能观察到「新 keys + 旧 values」两个数组，长度不一致
// ⇒ values[idx] 越界（AIOOBE）；且两字段均无 volatile ⇒ 失效写入不可见。
// 现改为不可变 holder + 单个 volatile 字段原子发布。
//
// 复用 upload-modrinth-114.js 的 multipart 上传协议（Modrinth /v2/version 接受 multipart）。
const fs = require('fs');
const https = require('https');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();

const VER = '1.1.1-continue.6';
const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-116\\';

const builds = [
  { mc: '1.20.1' }, { mc: '1.19.4' }, { mc: '1.19.2' }, { mc: '1.18.2' },
].map(b => ({
  version_number: VER + '+' + b.mc,
  jar: STAGE + 'MTR-ANTE-' + VER + '+' + b.mc + '.jar',
  name: 'MTR-ANTE ' + VER + ' (MC ' + b.mc + ', Fabric+Forge)',
  game_versions: [b.mc], loaders: ['fabric', 'forge'],
}));

const changelog = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '本版修掉**一个真实的并发缺陷**：滚转排序缓存的可见性与数组配对。',
  '',
  '### 🔴 滚转缓存的双字段竞态（AIOOBE / NPE 崩溃）',
  '',
  '**背景**：`RailMixin` 缓存了滚转角度的排序键与排序值，',
  '供 `RailExtraSupplier.getRollAngle()` 每帧二分查询使用',
  '（多车多线存档下每帧上千次调用）。',
  '',
  '**旧实现的问题**：缓存是**两个独立字段**，且**读写顺序相反**：',
  '',
  '| | 顺序 |',
  '|---|---|',
  '| 写端 `nteRebuildRollCache()` | 先赋 `values` → 再赋 `keys` |',
  '| 读端 `getRollAngle()` | 先取 `keys` → 再取 `values` |',
  '',
  '⇒ 渲染线程可能观察到「**新 keys + 旧 values**」这两个数组，',
  '两者长度不一致 ⇒ `values[idx]` **越界（AIOOBE）**。',
  '',
  '此外两字段都**没有 `volatile`**：',
  '',
  '- 编辑线程的失效写入对渲染线程**不可见**（无 happens-before）',
  '  ⇒ 滚转数据可能不更新；',
  '- `nte$getSortedRollKeys()` 在 `invalidate` 与 `rebuild` 之间',
  '  **可能返回 null** ⇒ `getRollAngle` 里 `keys.length` **NPE**。',
  '',
  '**修复**：把两个数组放进**不可变 holder**（`NteRollCache`），',
  '用**单个 `volatile` 字段**发布：',
  '',
  '- `volatile` 写 = happens-before ⇒ **可见性**有保证；',
  '- 单字段发布 ⇒ keys 与 values **必然来自同一次重建、长度必然一致**。',
  '',
  '三处失效点（`setRollAngleMap` / messagepack 反序列化 / 网络包反序列化）',
  '简化为「置一个字段 null」。',
  '',
  '### 验证',
  '',
  '新增 `tools/VerifyRollCacheVisibility.java`（独立 Java 程序，不依赖 MC / GraalVM），',
  '并接入 gradle 任务 `verifyRollCacheVisibility` ⇒ **PASS=8 FAIL=0**。',
  '',
  '压力测试（写线程持续重建 + 读线程按**生产代码的顺序**取用并索引）：',
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
  'MC 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1，每个版本都是 Fabric + Forge 合并包。',
  '',
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
          console.log('  id=' + j.id + ' size=' + j.files[0].size
            + ' sha512=' + j.files[0].hashes.sha512.slice(0, 16));
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
