/**
 * CobbleMarket —— 外部签名码的**测试网站**（零依赖，Node 18+）
 *
 * 用途：在本地跑通一条完整的「网站卖 RP」链路，验证协议本身 ——
 *   玩家在网页上选档位 → 点「我已支付」（**这一步是模拟的**，不接任何真实支付，点了就签发）
 *   → 网页给出兑换码 → 进游戏在「RP 市场 → 兑换」里兑换 → 到账。
 *
 * 它不是给服主直接用的成品（上线要自己接支付渠道、记账、防刷），
 * 只演示**签名那一段** —— 正好用来验证 `docs/points_external_code_zh.md` 里的协议描述
 * 是不是第三方照着就能实现。
 *
 * 用法：
 *   node tools/website-test/server.js                        # 8080 端口，secret 读环境变量 CM_SECRET
 *   node tools/website-test/server.js 8080 my-secret-here    # 指定端口与 secret
 *   node tools/website-test/server.js selftest               # 只跑自检：复现文档里的两个测试向量
 *
 * ⚠ secret 必须与服务端配置里的「外部签名码的 HMAC 密钥」**完全相同**，否则签出来的码不认。
 * ⚠ secret 只出现在这里（服务端进程），**绝不能写进页面** —— 进了前端就等于把印钞机交出去。
 */

'use strict';

const http = require('http');
const crypto = require('crypto');

// ── 协议常量（照 docs/points_external_code_zh.md「三、二进制布局」）──

const VERSION = 0x01;
const PAYLOAD_BYTES = 15;      // version(1)+serial(6)+amount(4)+expiresDays(2)+flags(1)+reserved(1)
const TOTAL_BYTES = 20;        // + signature(5)
const SIGNATURE_BYTES = 5;

/** Crockford Base32：标准 Base32 去掉易混淆的 I / L / O / U */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 过期日基准：自 2026-01-01 UTC 起的天数（= 1767225600 / 86400 = 20454） */
const EPOCH_DAYS = Math.floor(Date.parse('2026-01-01T00:00:00Z') / 1000 / 86400);

const MS_PER_DAY = 86400000;

/** 20 字节 → 32 个字符：按大端位序每 5 bit 取一个值，无 padding */
function base32Encode(bytes) {
  let out = '';
  let buffer = 0;
  let bits = 0;
  for (const b of bytes) {
    buffer = (buffer << 8) | b;   // 32 位内不会溢出：最多攒 12 bit 就吐一次
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(buffer >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(buffer << (5 - bits)) & 31];
  return out;
}

/** 按 4 位一组加连字符（展示用；服务器解析时会归一化掉） */
function format(code32) {
  return code32.match(/.{4}/g).join('-');
}

/**
 * 签发一个码。
 * @param secret    HMAC 密钥（UTF-8 转字节）
 * @param amount    RP 面额，必须 > 0
 * @param validDays 有效天数；0 = 不过期
 * @param serialHex 固定 serial（自检用，6 字节 hex）；不给就随机
 */
function sign(secret, amount, validDays, serialHex) {
  if (!(amount > 0)) throw new Error('面额必须大于 0');
  const payload = Buffer.alloc(PAYLOAD_BYTES);
  payload.writeUInt8(VERSION, 0);

  const serial = serialHex
    ? Buffer.from(serialHex, 'hex')
    : crypto.randomBytes(6);   // serial 必须用密码学安全随机源：撞车会让后一张码被判「已用过」
  if (serial.length !== 6) throw new Error('serial 必须是 6 字节（12 位 hex）');
  serial.copy(payload, 1);

  payload.writeInt32BE(amount, 7);            // 大端 int32

  let expiresDays = 0;
  if (validDays > 0) {
    // 照文档：floor((签发时刻毫秒 + 天数 × 86400000) / 86400000) - 20454
    expiresDays = Math.floor((Date.now() + validDays * MS_PER_DAY) / MS_PER_DAY) - EPOCH_DAYS;
    if (expiresDays > 0xFFFF) throw new Error('有效期超过协议上限（uint16 天）');
  }
  payload.writeUInt16BE(expiresDays, 11);
  payload.writeUInt8(0, 13);                  // flags，保留位
  payload.writeUInt8(0, 14);                  // reserved，保留位

  // 签名输入是 payload 的**原始 15 字节**（不是 Base32 串、不是 hex 文本），取 HMAC 前 5 字节
  const signature = crypto
    .createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(payload)
    .digest()
    .subarray(0, SIGNATURE_BYTES);

  return format(base32Encode(Buffer.concat([payload, signature])));
}

// ── 自检：复现 docs/points_external_code_zh.md「四、测试向量」 ──

function selftest() {
  const secret = 'cobblemarket-test-vector-secret';
  const vectors = [
    { amount: 100, serial: '0a1b2c3d4e5f', expect: '0451-PB1X-9SFG-0000-CG00-0000-D6R3-ZAYS' },
    { amount: 500, serial: 'ffffffffffff', expect: '07ZZ-ZZZZ-ZZZG-0001-YG00-0000-965D-EAQW' },
  ];
  let ok = true;
  for (const v of vectors) {
    const got = sign(secret, v.amount, 0, v.serial);
    const pass = got === v.expect;
    ok = ok && pass;
    console.log(`${pass ? 'PASS' : 'FAIL'}  amount=${v.amount} serial=${v.serial}`);
    console.log(`      got    ${got}`);
    console.log(`      expect ${v.expect}`);
  }
  console.log(ok ? '\n自检通过：签名实现与文档测试向量逐字符一致 ✅' : '\n自检失败 ❌');
  process.exit(ok ? 0 : 1);
}

// ── 网页（演示用，故意做得很土：这里只关心协议能不能跑通）──

const TIERS = [
  { id: 't1', label: '60 RP', amount: 60, price: '¥6' },
  { id: 't2', label: '300 RP', amount: 300, price: '¥30' },
  { id: 't3', label: '680 RP', amount: 680, price: '¥68' },
];

/** 默认有效期（天）。想测「过期」就把它调小、或签完把服务端的有效期上限调小 */
const DEFAULT_VALID_DAYS = 30;

const PAGE = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>CobbleMarket RP 充值（测试站）</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 560px; margin: 48px auto; padding: 0 16px; color: #222; }
  h1 { font-size: 20px; }
  .warn { background: #fff4e5; border: 1px solid #ffcc80; padding: 10px 12px; border-radius: 6px; font-size: 13px; }
  .tier { display: flex; justify-content: space-between; align-items: center; border: 1px solid #ddd;
          border-radius: 6px; padding: 10px 14px; margin: 8px 0; }
  button { background: #2e7d32; color: #fff; border: 0; border-radius: 4px; padding: 8px 16px; cursor: pointer; }
  button:disabled { background: #aaa; cursor: default; }
  code { font-size: 18px; letter-spacing: 1px; background: #f2f2f2; padding: 8px 12px; border-radius: 4px;
         display: block; margin-top: 10px; word-break: break-all; }
  .hint { color: #666; font-size: 13px; }
</style>
</head>
<body>
<h1>CobbleMarket RP 充值 —— 测试站</h1>
<p class="warn">⚠ 这是**测试用**页面：点「我已支付」不会真的扣钱，直接就把兑换码发给你。
真正上线要自己接支付渠道、记账、防刷。</p>
<div id="tiers"></div>
<div id="result"></div>
<p class="hint">拿到码后进游戏 → RP 市场 → 兑换框粘贴 → 点「兑换」。</p>
<script>
const tiers = ${JSON.stringify(TIERS)};
const box = document.getElementById('tiers');
const result = document.getElementById('result');
for (const t of tiers) {
  const row = document.createElement('div');
  row.className = 'tier';
  row.innerHTML = '<span><b>' + t.label + '</b> <span class="hint">' + t.price + '</span></span>';
  const btn = document.createElement('button');
  btn.textContent = '我已支付';
  btn.onclick = async () => {
    btn.disabled = true;
    result.innerHTML = '<p class="hint">签发中…</p>';
    try {
      const res = await fetch('/api/pay', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tier: t.id })
      });
      const data = await res.json();
      result.innerHTML = res.ok
        ? '<p>兑换码（点一下自动全选）：</p><code onclick="getSelection().selectAllChildren(this)">' + data.code + '</code>'
        : '<p style="color:#c62828">签发失败：' + data.error + '</p>';
    } catch (e) {
      result.innerHTML = '<p style="color:#c62828">请求失败：' + e + '</p>';
    }
    btn.disabled = false;
  };
  row.appendChild(btn);
  box.appendChild(row);
}
</script>
</body>
</html>`;

// ── HTTP 服务 ──

function start(port, secret) {
  if (!secret) {
    console.error('缺少 secret。用法：node server.js [端口] <secret>，或设环境变量 CM_SECRET');
    process.exit(1);
  }
  const server = http.createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url.startsWith('/?'))) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(PAGE);
      return;
    }

    if (req.method === 'POST' && req.url === '/api/pay') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        try {
          const { tier } = JSON.parse(body || '{}');
          const t = TIERS.find((x) => x.id === tier);
          if (!t) throw new Error('档位不存在');
          // ⚠ 真实站点：先收款、记订单号，**确认到账后**才签发。
          // 这里模拟成「点一下就成功」，所以直接签。
          const code = sign(secret, t.amount, DEFAULT_VALID_DAYS, null);
          console.log(`[pay] tier=${t.id} amount=${t.amount} -> ${code.slice(0, 9)}…`);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ code }));
        } catch (e) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: String(e.message || e) }));
        }
      });
      return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('not found');
  });
  server.listen(port, () => {
    console.log(`测试站已启动：http://localhost:${port}`);
    console.log(`有效期 ${DEFAULT_VALID_DAYS} 天；secret 长度 ${secret.length}（务必与游戏服务端配的一致）`);
  });
}

// ── 入口 ──

const [, , arg1, arg2] = process.argv;
if (arg1 === 'selftest') {
  selftest();
} else {
  start(parseInt(arg1 || '8080', 10), arg2 || process.env.CM_SECRET || '');
}
