/**
 * CarDesk「云端应用库」上传端 —— Cloudflare Worker
 *
 * ## 它解决什么
 *
 * 车机要装别的 App（导航、音乐、视频…）时，只有两条老路：
 *
 * - 带 U 盘到车上插；
 * - 在车上让电脑和车机连**同一个局域网**，用 CarDesk 的「局域网传输」推过去。
 *
 * 两条路都要求**人在车旁边**。这个 Worker 把 APK 先放到 Cloudflare R2，
 * 车机再从公网拉 —— **出门前在手机上上传，到车上直接装**：
 * 不用带 U 盘，也不用和车机连同一个局域网（车机走 4G 就行）。
 *
 * ## 为什么必须有 Worker，而不是「网页直接写 R2」
 *
 * R2 的写入必须带 S3 凭据（S3 Access Key）。凭据一旦写进浏览器里的网页，
 * **等于公开** —— 谁打开这个页面就能拿到密钥往你的 bucket 里塞东西，
 * 还能覆盖你已有的文件。
 *
 * 所以网页只负责发请求，**真正的写入发生在 Worker 里**：
 * Worker 用 R2 binding 读写 bucket（凭据由 Cloudflare 托管，
 * 既不进代码、也不进仓库、也不进手机浏览器）。
 *
 * ## 鉴权：写操作要口令，读操作不要
 *
 * - **写**（上传 / 删除）必须带 `x-cardesk-token` 头，值等于 Worker 的
 *   `UPLOAD_TOKEN` secret。没有配置 secret 时**一律拒绝**（fail closed）——
 *   宁可上传不了，也不能变成一个谁都能往 bucket 里写东西的公共接口。
 * - **读**（取清单）不鉴权：车机走公开自定义域匿名读，
 *   和「在线更新」是同一条路（`file.chuxi.bid`）。
 *
 * ## 清单
 *
 * 每次上传 / 删除后重写 `cardesk/apks.json`，车机读的就是它。
 * 清单格式见 `worker/README.md`，与 `tools/publish_apks.py` 产出的**完全一致**。
 *
 * ## 体积上限
 *
 * Workers 免费版单次请求体上限 100MB，这里也显式拦一道，
 * 免得传大包时传一半失败、用户还以为传成功了。
 */

/** APK 对象的 key 前缀（车机按这个前缀拼下载地址）。 */
const PREFIX = 'cardesk/apks/';

/** 清单的 object key（车机固定拉这个地址）。 */
const INDEX_KEY = 'cardesk/apks.json';

/** 单个 APK 的体积上限：100MB（Workers 免费版请求体上限）。 */
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

/** 上传口令所在的请求头。 */
const TOKEN_HEADER = 'x-cardesk-token';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === 'GET' && url.pathname === '/') {
        return html(UPLOAD_PAGE);
      }
      if (request.method === 'GET' && url.pathname === '/api/index') {
        return await handleIndex(env);
      }
      if (request.method === 'POST' && url.pathname === '/api/upload') {
        return await handleUpload(request, env, url);
      }
      if (request.method === 'POST' && url.pathname === '/api/delete') {
        return await handleDelete(request, env);
      }
      return json({ ok: false, error: '没有这个接口' }, 404);
    } catch (e) {
      // 兜底：Worker 崩了也要给页面一个能看懂的 JSON，而不是 500 空白页
      return json({ ok: false, error: describe(e) }, 500);
    }
  },
};

// ------------------------------------------------------------------ 读清单

/**
 * 取清单。
 *
 * 直接回传 R2 里存的那份 `cardesk/apks.json`（原样），
 * 这样**网页看到的和车机看到的永远是同一份**，不会出现两套生成逻辑对不上。
 */
async function handleIndex(env) {
  const obj = await env.APKS.get(INDEX_KEY);
  if (!obj) {
    return json({ schema: 1, updatedAt: '', items: [] });
  }
  return new Response(await obj.text(), {
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

// ------------------------------------------------------------------ 上传

async function handleUpload(request, env, url) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: '口令不对' }, 403);
  }
  const file = sanitizeFileName(url.searchParams.get('file') || '');
  if (!file) return json({ ok: false, error: '缺少文件名' }, 400);
  if (!/\.apk$/i.test(file)) return json({ ok: false, error: '只接受 .apk 文件' }, 400);

  const name = (url.searchParams.get('name') || '').trim().slice(0, 60);

  // 先看 Content-Length —— 超限就别收，省得白传一遍
  const declared = Number(request.headers.get('content-length') || '0');
  if (declared > MAX_UPLOAD_BYTES) {
    return json({ ok: false, error: '文件超过 100MB 上限' }, 413);
  }

  const buf = await request.arrayBuffer();
  if (buf.byteLength === 0) return json({ ok: false, error: '文件是空的' }, 400);
  if (buf.byteLength > MAX_UPLOAD_BYTES) {
    return json({ ok: false, error: '文件超过 100MB 上限' }, 413);
  }

  const sha256 = await sha256Hex(buf);
  const addedAt = new Date().toISOString();
  const key = PREFIX + file;

  await env.APKS.put(key, buf, {
    httpMetadata: { contentType: 'application/vnd.android.package-archive' },
    // 摘要和展示名放在 customMetadata 里：list 就能直接读到，
    // 不用把对象再下载一遍重新算哈希
    customMetadata: {
      sha256,
      // 展示名要进对象的元数据，而元数据（S3 侧就是 x-amz-meta-* 头）**只能是 ASCII** ——
      // 头值按 latin-1 编码，中文放进去会直接报 'latin-1' codec can't encode。
      // 所以这里存百分号编码后的 ASCII，读的时候再解回来。
      appName: encodeURIComponent(name || file.replace(/\.apk$/i, '')),
      addedAt,
    },
  });

  const doc = await rebuildIndex(env);
  return json({ ok: true, key, size: buf.byteLength, sha256, index: doc.items.length });
}

// ------------------------------------------------------------------ 删除

async function handleDelete(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: '口令不对' }, 403);
  }
  let key = '';
  try {
    const body = await request.json();
    key = String((body && body.key) || '');
  } catch (e) {
    return json({ ok: false, error: '请求体不是 JSON' }, 400);
  }
  // 只许删自己前缀下的东西：别让一个 key 把整个 bucket 端了
  if (!key.startsWith(PREFIX) || key.includes('..')) {
    return json({ ok: false, error: '只能删 cardesk/apks/ 下的文件' }, 400);
  }
  await env.APKS.delete(key);
  await rebuildIndex(env);
  return json({ ok: true, key });
}

// ------------------------------------------------------------------ 清单重建

/**
 * 扫一遍前缀下的 APK，重写清单。
 *
 * 清单里的每一项都带上 `sha256` —— 车机下载完会拿它校验，
 * 对不上就当没下成功（公网下载被截断不会报错，只看 HTTP 200 不够）。
 */
async function rebuildIndex(env) {
  const items = [];
  let cursor;
  // list 是分页的：不跟到底会漏掉后面的包（而且是**静默**漏，更难查）
  for (;;) {
    const listed = await env.APKS.list(cursor ? { prefix: PREFIX, cursor } : { prefix: PREFIX });
    for (const o of listed.objects) {
      if (!o.key.startsWith(PREFIX) || !o.key.endsWith('.apk')) continue;
      const meta = o.customMetadata || {};
      items.push({
        name: decodeMeta(meta.appName) || o.key.slice(PREFIX.length).replace(/\.apk$/i, ''),
        file: o.key.slice(PREFIX.length),
        key: o.key,
        size: o.size,
        sha256: meta.sha256 || '',
        addedAt: meta.addedAt || new Date(o.uploaded).toISOString(),
      });
    }
    if (!listed.truncated || !listed.cursor) break;
    cursor = listed.cursor;
  }
  // 新的在前
  items.sort((a, b) => (a.addedAt < b.addedAt ? 1 : a.addedAt > b.addedAt ? -1 : 0));

  const doc = { schema: 1, updatedAt: new Date().toISOString(), items };
  await env.APKS.put(INDEX_KEY, JSON.stringify(doc), {
    httpMetadata: { contentType: 'application/json; charset=utf-8' },
  });
  return doc;
}

// ------------------------------------------------------------------ 工具

/**
 * 写操作的口令校验。
 *
 * 没配 `UPLOAD_TOKEN` 时**一律拒绝** —— 这个 Worker 的地址是公开的，
 * 没口令就等于把 bucket 写权限送人。
 */
function authorized(request, env) {
  const expect = env.UPLOAD_TOKEN;
  if (!expect) return false;
  const got = request.headers.get(TOKEN_HEADER) || '';
  return got.length > 0 && got === expect;
}

/**
 * 文件名净化：只留最后一个路径段，去掉路径分隔和控制字符。
 *
 * 上传来的文件名是**不可信输入**，不能让它带着 `../` 或路径分隔符写进 key。
 */
function sanitizeFileName(raw) {
  const base = String(raw).split(/[\\/]/).pop() || '';
  // \p{L} 覆盖中日韩等字母，\p{N} 覆盖数字
  const cleaned = base.replace(/[^\p{L}\p{N}._\- ()]/gu, '_').trim();
  if (!cleaned || cleaned === '.' || cleaned === '..') return '';
  return cleaned.slice(0, 120);
}

async function sha256Hex(buf) {
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * 解开百分号编码的展示名。
 *
 * 存的是 `encodeURIComponent` 过的 ASCII（对象元数据只能放 ASCII），
 * 这里解回来。解不了就原样返回 —— 名字难看点总比整条记录丢掉强。
 */
function decodeMeta(value) {
  if (!value) return '';
  try {
    return decodeURIComponent(value);
  } catch (e) {
    return String(value);
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

function html(body) {
  return new Response(body, {
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

function describe(e) {
  if (!e) return '未知错误';
  return String(e.message || e);
}

// ------------------------------------------------------------------ 上传页

/**
 * 上传页（内联在 Worker 里，单文件部署，不用管静态资源）。
 *
 * 界面按**手机**做的：一次只干一件事（选文件 → 传），
 * 下面顺手列一下已经有的包，方便删掉传错的。
 */
const UPLOAD_PAGE = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CarDesk 云端应用库</title>
<style>
:root { color-scheme: light dark; }
* { -webkit-tap-highlight-color: transparent; }
body {
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  margin: 0; padding: 18px; background: #f4f6f9; color: #1b1f27;
}
@media (prefers-color-scheme: dark) { body { background: #14171f; color: #e8ebf1; } }
.card {
  background: #fff; border: 1px solid #e2e5eb; border-radius: 16px;
  padding: 18px; margin: 0 0 14px;
}
@media (prefers-color-scheme: dark) { .card { background: #1e222b; border-color: #2d323e; } }
h1 { font-size: 20px; margin: 0 0 4px; }
.sub { font-size: 13px; opacity: .68; margin: 0; line-height: 1.6; }
label { display: block; font-size: 13px; opacity: .8; margin: 14px 0 6px; }
input {
  width: 100%; box-sizing: border-box; padding: 11px 12px; font-size: 15px;
  border: 1px solid #d4d8e0; border-radius: 10px; background: #fff; color: inherit;
}
@media (prefers-color-scheme: dark) { input { background: #262b36; border-color: #39404f; } }
button {
  width: 100%; margin-top: 14px; padding: 13px; font-size: 16px; font-weight: 600;
  border-radius: 10px; border: 1px solid #d4d8e0; background: #0f766e; color: #fff; cursor: pointer;
}
button:disabled { opacity: .55; }
button.ghost { background: #eef0f4; color: #1b1f27; }
@media (prefers-color-scheme: dark) { button.ghost { background: #262b36; color: #e8ebf1; } }
button.del { width: auto; margin: 0; padding: 8px 14px; font-size: 13px; background: #fde8e8; color: #b42318; }
@media (prefers-color-scheme: dark) { button.del { background: #4a2323; color: #fca5a5; } }
.row { display: flex; align-items: center; gap: 12px; padding: 13px 0; border-top: 1px solid rgba(128,128,128,.18); }
.row .meta { flex: 1; min-width: 0; }
.row .nm { font-size: 15px; font-weight: 600; }
.row .s2 { font-size: 12px; opacity: .66; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 3px; }
#log { font-size: 13px; white-space: pre-wrap; line-height: 1.6; margin-top: 12px; opacity: .85; }
#bar { width: 100%; height: 8px; margin-top: 12px; }
.note { font-size: 12px; opacity: .6; margin-top: 14px; line-height: 1.7; }
</style>
</head>
<body>
<div class="card">
  <h1>CarDesk 云端应用库</h1>
  <p class="sub">把 APK 传到 Cloudflare R2，车机上打开「云端应用」就能直接下载安装。<br>出门前传好，到车上不用再插 U 盘。</p>

  <label for="token">上传口令</label>
  <input id="token" type="password" placeholder="Worker 的 UPLOAD_TOKEN" autocomplete="off">

  <label for="file">APK 文件</label>
  <input id="file" type="file" accept=".apk,application/vnd.android.package-archive">

  <label for="name">应用名（可留空，默认用文件名）</label>
  <input id="name" type="text" placeholder="例如：酷狗音乐" autocomplete="off">

  <button id="up">上传</button>
  <progress id="bar" value="0" max="100" hidden></progress>
  <div id="log"></div>
  <p class="note">单个文件上限 100MB。上传后车机需要刷新一下「云端应用」列表才能看到。</p>
</div>

<div class="card">
  <div style="display:flex;align-items:center;gap:10px;">
    <h1 style="flex:1;font-size:17px;margin:0;">已上传的应用</h1>
    <button id="refresh" class="ghost" style="width:auto;margin:0;padding:9px 16px;font-size:14px;">刷新</button>
  </div>
  <div id="list" style="margin-top:6px;"></div>
</div>

<script>
const $ = (id) => document.getElementById(id);
const tokenEl = $('token'), fileEl = $('file'), nameEl = $('name');
const logEl = $('log'), listEl = $('list'), barEl = $('bar'), upBtn = $('up');

// 口令存在本机浏览器里：每次打开不用重输
try { tokenEl.value = localStorage.getItem('cardesk_token') || ''; } catch (e) {}
tokenEl.addEventListener('change', () => {
  try { localStorage.setItem('cardesk_token', tokenEl.value.trim()); } catch (e) {}
});

function fmtSize(n) {
  if (!n) return '';
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return (n / 1024).toFixed(0) + ' KB';
  return n + ' B';
}

function say(text) { logEl.textContent = text; }

async function refresh() {
  listEl.innerHTML = '<div class="sub" style="padding:12px 0;">读取中…</div>';
  try {
    const res = await fetch('/api/index', { cache: 'no-store' });
    const doc = await res.json();
    const items = doc.items || [];
    if (!items.length) {
      listEl.innerHTML = '<div class="sub" style="padding:12px 0;">还没有应用，传一个上去。</div>';
      return;
    }
    listEl.innerHTML = '';
    for (const it of items) {
      const row = document.createElement('div');
      row.className = 'row';
      const meta = document.createElement('div');
      meta.className = 'meta';
      const nm = document.createElement('div');
      nm.className = 'nm';
      nm.textContent = it.name || it.file;
      const s2 = document.createElement('div');
      s2.className = 's2';
      s2.textContent = fmtSize(it.size) + ' · ' + (it.addedAt || '').slice(0, 16).replace('T', ' ') + ' · ' + it.file;
      meta.appendChild(nm); meta.appendChild(s2);
      const del = document.createElement('button');
      del.className = 'del';
      del.textContent = '删除';
      del.onclick = async () => {
        if (!confirm('删除「' + (it.name || it.file) + '」？车机上会同步消失。')) return;
        try {
          const res = await fetch('/api/delete', {
            method: 'POST',
            headers: { 'content-type': 'application/json', 'x-cardesk-token': tokenEl.value.trim() },
            body: JSON.stringify({ key: it.key }),
          });
          const doc2 = await res.json();
          say(doc2.ok ? '已删除：' + it.file : '删除失败：' + (doc2.error || res.status));
          refresh();
        } catch (e) {
          say('删除失败：' + e.message);
        }
      };
      row.appendChild(meta); row.appendChild(del);
      listEl.appendChild(row);
    }
  } catch (e) {
    listEl.innerHTML = '<div class="sub" style="padding:12px 0;">读清单失败：' + e.message + '</div>';
  }
}

upBtn.onclick = () => {
  const f = fileEl.files && fileEl.files[0];
  if (!f) { say('先选一个 .apk 文件'); return; }
  const token = tokenEl.value.trim();
  if (!token) { say('先填上传口令'); return; }
  if (f.size > 104857600) { say('文件超过 100MB 上限'); return; }

  const name = nameEl.value.trim() || f.name.replace(/\\.apk$/i, '');
  say('上传中：' + f.name);
  upBtn.disabled = true;
  barEl.hidden = false;
  barEl.value = 0;

  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/upload?name=' + encodeURIComponent(name) + '&file=' + encodeURIComponent(f.name));
  xhr.setRequestHeader('x-cardesk-token', token);
  xhr.setRequestHeader('content-type', 'application/vnd.android.package-archive');
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) barEl.value = e.loaded * 100 / e.total;
  };
  xhr.onload = () => {
    upBtn.disabled = false;
    barEl.hidden = true;
    let doc = {};
    try { doc = JSON.parse(xhr.responseText); } catch (e) {}
    if (xhr.status === 200 && doc.ok) {
      say('上传成功：' + doc.key + '\\n大小 ' + fmtSize(doc.size) + '\\nSHA-256 ' + doc.sha256 + '\\n清单里现在有 ' + doc.index + ' 个包');
      fileEl.value = '';
      refresh();
    } else {
      say('上传失败（HTTP ' + xhr.status + '）：' + (doc.error || xhr.responseText || '未知原因'));
    }
  };
  xhr.onerror = () => {
    upBtn.disabled = false;
    barEl.hidden = true;
    say('上传失败：网络错误');
  };
  xhr.send(f);
};

$('refresh').onclick = refresh;
refresh();
</script>
</body>
</html>`;
