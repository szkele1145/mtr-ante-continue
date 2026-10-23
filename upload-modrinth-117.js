// MTR-ANTE Continued 1.1.1-continue.7 —— Modrinth 上传脚本
//
// 本版内容：脚本执行预算自适应（区分「慢脚本」与「死循环」）
//          + 修掉 VerifyScriptTimeout 的偶发假失败。
//
// 复用 upload-modrinth-116.js 的 multipart 上传协议（Modrinth /v2/version 接受 multipart）。
const fs = require('fs');
const https = require('https');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();

const VER = '1.1.1-continue.7';
const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-117\\';

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
  '本版把「**慢脚本**」和「**死循环**」区分开，并修掉一处**回归测试偶发假失败**。',
  '',
  '### 1. 脚本执行预算自适应',
  '',
  '**问题**：看门狗是**固定 10 秒**墙钟超时。一个合法但重的脚本',
  '（例如首次构建几何体要 12 秒）会和死循环一样被砍掉，',
  '而且日志里两者长得一模一样，作者无法判断到底是自己写慢了还是写错了。',
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
  '`VerifyScriptTimeout` 的事实 2/3/4 原先靠 `started2.countDown()` + 固定',
  '`Thread.sleep(500)` 来「等脚本跑起来」。机器负载高时（gradle daemon + 后台构建）',
  'victim 线程可能还没进入 polyglot 执行体，`interrupt` 就找不到可中断的目标，',
  '或 1 秒等待窗口在排队中耗尽 ⇒ **5 条断言随机失败**（实测遇到过一次）。',
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
  '4 倍放大、下限抬升、上限夹取、以及两条不变式（预算恒在 [10 s, 60 s]、',
  '耗时样本越大预算不减）。',
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
