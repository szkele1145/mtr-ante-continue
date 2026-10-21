// MTR-ANTE Continued 1.1.1-continue.5 —— Modrinth 上传脚本
//
// 本版内容：JS 脚本超时看门狗的线程爆炸修复（ScriptHolderBase.startWatchdog）。
// 原实现每次脚本调用新建 2 个一次性线程，而 tryCallRenderFunctionAsync 是
// 每帧每脚本调用一次 => 60 FPS 下每脚本每秒创建 120 个线程对象。
// 现改为静态 ScheduledExecutorService（1 个 daemon 线程）+ schedule 一次性任务。
//
// 复用 upload-modrinth-114.js 的 multipart 上传协议（Modrinth /v2/version 接受 multipart）。
const fs = require('fs');
const https = require('https');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();

const VER = '1.1.1-continue.5';
const STAGE = 'F:\\vibe coding工程\\mtr-ante\\release-115\\';

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
  '本版是一个**性能优化**版：修掉 JS 脚本超时看门狗里的线程爆炸问题。',
  '',
  '### 🟡 看门狗线程爆炸（本次唯一改动）',
  '',
  '**背景**：continue.4 给 JS 脚本加了 10 秒超时看门狗，',
  '用 GraalVM 的 `Context.interrupt(Duration)` 中断死循环脚本。',
  '看门狗本身跑在**每次调用都新建**的 2 个一次性线程上',
  '（watchdog 轮询线程 + watcher 等待线程）。',
  '',
  '**问题**：`tryCallRenderFunctionAsync` 是**每帧每脚本**调用一次的，',
  '所以每个脚本上下文每秒会创建 **120** 个线程对象（60 FPS × 2）。',
  '资源包脚本一多（多列车 / 多景物各一个 context），',
  '短时间内就会堆积数千个线程 —— 栈内存与 GC 压力显著，',
  '高负载下会拖垮服务端。',
  '',
  '**修复**：',
  '',
  '- 改为**单一静态 `ScheduledExecutorService`**（1 个 daemon 线程）',
  '  调度看门狗任务；',
  '- 每次调用只 **schedule** 一个延迟 10 秒的一次性任务，**不新建线程**；',
  '- 任务触发时用 `task.isDone()` 判断脚本是否已提前完成 ——',
  '  若是则直接返回（等价于原来的 `done` 标志 + watcher 线程）；',
  '  若否则执行 `context.interrupt(Duration)`。',
  '',
  '**超时语义完全不变**：10 秒超时 / 1 秒宽限 / `AtomicBoolean` 保证只中断一次。',
  '',
  '### 验证',
  '',
  '`tools/VerifyScriptTimeout.java` 扩展到 **PASS=14 FAIL=0**（原 9 项 + 新 5 项）：',
  '',
  '| # | 事实 | 结果 |',
  '|---|---|---|',
  '| 1 | 死循环脚本 1.5s 后仍在运行（问题真实存在） | ok |',
  '| 2 | `Context.interrupt(Duration)` <5s 内中断，脚本线程退出 | ok |',
  '| 3 | 抛出 `PolyglotException`（既有 `catch (Exception)` 能接住） | ok |',
  '| 4 | 中断后 context 仍可继续执行后续脚本 | ok |',
  '| 5 | 正常百万次循环脚本不被误杀 | ok |',
  '| 6 | 提前完成的脚本不会被中断（`isDone` 短路），context 仍可用 | ok |',
  '| 7 | 超时脚本被中断并退出，抛 `PolyglotException` | ok |',
  '| 8 | **连续 1000 次调度后看门狗线程数仍为 1**（未线程爆炸） | ok |',
  '',
  '第 8 项是本次优化的核心断言。',
  '',
  '### 版本矩阵',
  '',
  'MC 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1，每个版本都是 Fabric + Forge 合并包。',
  '',
  '### 上一版回顾',
  '',
  'continue.4 修复了审查报告最后一项遗留问题（#9 脚本执行超时）；',
  'continue.3 修复了 #7 / #8 / #10 / #11 / #13 / #14。',
  '审查报告 7 项**全部闭合**。',
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
