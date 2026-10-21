const fs = require('fs');
const https = require('https');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const repo = 'szkele1145/mtr-ante-continue';
const VER = '1.1.1-continue.4';
const tag = 'v' + VER;

const assets = ['1.20.1', '1.19.4', '1.19.2', '1.18.2'].map(mc =>
  'F:\\vibe coding工程\\mtr-ante\\build\\MTR-ANTE-' + VER + '+' + mc + '.jar'
).filter(p => fs.existsSync(p));

const notes = [
  '## MTR-ANTE Continued ' + VER,
  '',
  '审查报告最后一项遗留问题（#9）闭环：脚本执行超时。',
  '',
  '| MC | Jar (Fabric+Forge 合并) |',
  '|---|---|',
  '| 1.20.1 | MTR-ANTE-' + VER + '+1.20.1.jar |',
  '| 1.19.4 | MTR-ANTE-' + VER + '+1.19.4.jar |',
  '| 1.19.2 | MTR-ANTE-' + VER + '+1.19.2.jar |',
  '| 1.18.2 | MTR-ANTE-' + VER + '+1.18.2.jar |',
  '',
  '### 🔴 脚本执行超时（#9）',
  '',
  '**问题**：资源包脚本里一个 `while(true){}` 会永久占住脚本线程，',
  '之后**所有**脚本（包括其它资源包的）全部排队卡死 ——',
  '游戏表现为「脚本功能全失」且不报错。',
  '',
  '**修复**：加 10 秒超时看门狗，超时用 GraalVM 24.x 官方',
  '`Context.interrupt(Duration)` 中断执行。',
  '',
  '- 中断后脚本线程收到 `PolyglotException`，走**原有**错误处理',
  '  （记录失败时间与异常），错误处理语义不变；',
  '- `interrupt` 再给 1 秒宽限（脚本若卡在 Java 宿主调用里不会死等）；',
  '- `AtomicBoolean` 保证只中断一次；',
  '- 看门狗是 daemon 线程，不占用脚本线程本身（那正是被卡住的那个）。',
  '',
  '### 构建',
  '',
  '- `build.ps1` 不再走 `gradlew`（wrapper 下载 gradle-8.11-bin.zip 被 TLS 中间盒',
  '  截断，`AEADBadTagException: Tag mismatch`），改调本机 gradle 8.11；',
  '  脚本参数化 `[mcVersion] [gradleTask]`。',
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
  '四个 MC 版本 `gradle build` 全部 BUILD SUCCESSFUL。',
  '',
  '### 本次遗留',
  '',
  '无。审查报告 7 项（#7 #8 #9 #10 #11 #13 #14）**全部闭**。'
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
  if (!sha) { console.error('用法: node publish-github-114.js <commit-sha>'); process.exit(1); }
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
      tag_name: tag, name: 'MTR-ANTE ' + VER + ' - 脚本执行超时（#9 闭环）',
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
