const fs = require('fs');
const https = require('https');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();

const VER = '1.1.1-continue.4';
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
  '修复审查报告最后一项遗留问题（#9），并把构建脚本改成不依赖 Gradle wrapper。',
  '',
  '### 🔴 脚本执行超时（#9，本次闭环）',
  '',
  '**问题**：资源包脚本里一个 `while(true){}` 会永久占住脚本线程，',
  '之后**所有**脚本（包括其它资源包的）全部排队卡死 ——',
  '游戏表现为「脚本功能全失」且不报错。',
  '',
  '**修复**：给脚本执行加 10 秒超时看门狗，超时用 GraalVM 24.x 官方',
  '`Context.interrupt(Duration)` 中断执行。',
  '',
  '- 中断后脚本线程收到 `PolyglotException`，走**原有**错误处理（记录失败时间与异常），',
  '  错误处理语义不变；',
  '- `interrupt` 再给 1 秒宽限（脚本若卡在 Java 宿主调用里不会死等）；',
  '- 用 `AtomicBoolean` 保证只中断一次；',
  '- 看门狗是 daemon 线程，不占用脚本线程本身（那正是被卡住的那个）。',
  '',
  '### 构建',
  '',
  '- `build.ps1` 不再走 `gradlew`：wrapper 下载 `gradle-8.11-bin.zip` 会被',
  '  TLS 中间盒截断（`AEADBadTagException: Tag mismatch`），改调本机 gradle 8.11；',
  '  脚本参数化 `[mcVersion] [gradleTask]`（默认 `1.20.1 build`）。',
  '',
  '### 验证',
  '',
  '新增 `tools/VerifyScriptTimeout.java` + gradle 任务 `verifyScriptTimeout`，',
  '用真实 GraalVM 24.2.2 独立验证 5 个事实，**PASS=9 FAIL=0**：',
  '',
  '| # | 事实 | 结果 |',
  '|---|---|---|',
  '| 1 | 死循环脚本 1.5s 后仍在运行（问题真实存在） | ok |',
  '| 2 | `Context.interrupt(Duration)` <5s 内中断，脚本线程退出 | ok |',
  '| 3 | 抛出 `PolyglotException`（既有 `catch (Exception)` 能接住） | ok |',
  '| 4 | **中断后 context 仍可继续执行后续脚本**（`1+1`=2） | ok |',
  '| 5 | 正常百万次循环脚本不被误杀 | ok |',
  '',
  '`gradle build` 四个 MC 版本全部 BUILD SUCCESSFUL。',
  '',
  '### 本次遗留',
  '',
  '无。审查报告 7 项（#7 #8 #9 #10 #11 #13 #14）**全部闭**。',
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
