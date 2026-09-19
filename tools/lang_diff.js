#!/usr/bin/env node
/**
 * CobbleMarket 语言文件查缺工具（零依赖，Node 18+）
 *
 * 模组目前只有 zh_cn / en_us 两份语言文件。以后加新语言时：
 *   ① 复制 `en_us.json` 成 `xx_xx.json`，把值翻译掉（这一步量最大，交给母语玩家）
 *   ② **之后每次模组加词条，用本工具列出这个语言缺了哪些** —— 翻译者只补增量（十几条），
 *      不用重翻。这就是"加了语言之后怎么维护"的答案。
 *
 * 用法：
 *   node tools/lang_diff.js                     # 总览：各语言相对英文的差异
 *   node tools/lang_diff.js de_de               # 详情：de_de 缺哪些 key（附英文原文）
 *   node tools/lang_diff.js --estimate de_de    # 估算：从零做 de_de 要翻多少条、多少字
 *
 * 退出码：0 = 全部对齐；1 = 有语言落后（可以挂到发布前的检查里）
 */

'use strict';

const fs = require('fs');
const path = require('path');

const LANG_DIR = path.join(
  __dirname, '..',
  'common', 'src', 'main', 'resources', 'assets', 'cobblemarket', 'lang'
);
const BASE = 'en_us';

/** 读一个语言文件；坏掉就报错退出（语言文件坏了等于界面乱码，值得直接失败） */
function load(name) {
  const file = path.join(LANG_DIR, `${name}.json`);
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`✗ ${name}.json 解析失败：${e.message}`);
    process.exit(2);
  }
}

/** 列出语言目录下所有 xx_xx.json（排除基准） */
function allLangs() {
  return fs.readdirSync(LANG_DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => f.replace(/\.json$/, ''))
    .sort();
}

function diff(lang, base) {
  const langs = load(lang) || {};
  const missing = Object.keys(base).filter((k) => !(k in langs));
  const extra = Object.keys(langs).filter((k) => !(k in base));
  const same = Object.keys(base).filter((k) => k in langs && langs[k] === base[k]);
  return { missing, extra, same };
}

function main() {
  const [, , arg1, arg2] = process.argv;
  const base = load(BASE);
  if (!base) {
    console.error(`找不到基准语言 ${BASE}.json`);
    process.exit(2);
  }
  const baseCount = Object.keys(base).length;

  const estimate = arg1 === '--estimate';
  const target = estimate ? arg2 : arg1;

  // ── 详情模式：某个语言缺什么 ──
  if (target) {
    const langs = load(target);
    if (!langs) {
      console.error(`没有 ${target}.json —— 新语言就是从这一份开始的：`);
      console.error(`  复制 ${BASE}.json 成 ${target}.json，把值翻译掉`);
      console.error(`  待翻译条数：${baseCount}`);
      process.exit(1);
    }
    const { missing, extra, same } = diff(target, base);
    console.log(`${target}.json：共 ${Object.keys(langs).length} 条，基准 ${BASE} 有 ${baseCount} 条`);
    console.log('');

    if (missing.length === 0) {
      console.log(`✅ 没有缺的词条`);
    } else {
      console.log(`⚠ 缺 ${missing.length} 条（下面这些 key 在 ${target} 下会显示成原始 key，玩家看到的是乱码）：`);
      console.log('');
      for (const k of missing) console.log(`  ${k}\n      ${BASE}: ${JSON.stringify(base[k])}`);
    }
    if (extra.length > 0) {
      console.log('');
      console.log(`ℹ ${extra.length} 条是基准里没有的（多半是改名/删掉的旧词条，可以清理）：`);
      for (const k of extra.slice(0, 20)) console.log(`  ${k}`);
      if (extra.length > 20) console.log(`  …还有 ${extra.length - 20} 条`);
    }
    const pct = ((1 - missing.length / baseCount) * 100).toFixed(1);
    console.log('');
    console.log(`完成度：${pct}%`);
    process.exit(missing.length === 0 ? 0 : 1);
  }

  // ── 总览模式 ──
  const langs = allLangs();
  console.log(`语言目录：${path.relative(process.cwd(), LANG_DIR)}`);
  console.log(`基准 ${BASE}：${baseCount} 条`);
  console.log('');
  let behind = 0;
  for (const l of langs) {
    const data = load(l);
    const count = Object.keys(data).length;
    if (l === BASE) {
      console.log(`  ${l.padEnd(10)} ${String(count).padStart(4)} 条   （基准）`);
      continue;
    }
    const { missing, extra } = diff(l, base);
    const pct = ((1 - missing.length / baseCount) * 100).toFixed(1);
    const flag = missing.length > 0 ? '⚠' : '✅';
    if (missing.length > 0) behind++;
    console.log(
      `  ${l.padEnd(10)} ${String(count).padStart(4)} 条   ${flag} 缺 ${String(missing.length).padStart(4)} 条（完成度 ${pct}%）` +
      (extra.length > 0 ? `，另有 ${extra.length} 条陈旧` : '')
    );
  }

  if (estimate) {
    console.log('');
    console.log(`若要新做一份语言，需要翻 ${baseCount} 条`);
  }

  console.log('');
  if (behind === 0) {
    console.log('✅ 所有语言都与基准对齐');
  } else {
    console.log(`⚠ ${behind} 个语言落后于基准 —— 让翻译者补增量：node tools/lang_diff.js <语言>`);
  }
  process.exit(behind === 0 ? 0 : 1);
}

main();
