// Đối chiếu tên test: mọi it(...) của backend/test/*.test.js phải có @DisplayName cùng tên trong src/test.
// Dùng: node scripts/check-test-parity.mjs [thư-mục-test-node]
// Thoát 0 khi đủ; khác 0 khi thiếu tên, còn @Disabled hoặc có tên không đọc được.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const nodeTestDir = resolve(process.argv[2] ?? join(here, '..', '..', 'backend', 'test'));
const javaTestDir = resolve(here, '..', 'src', 'test');

// Test Node được miễn, khớp đúng tên it(...), kèm lý do.
const BROWSER_ONLY = 'hàm chỉ chạy trên trình duyệt (phoneDigits/toPhone/phoneInputError), server không gọi';
const EXEMPT = new Map([
  ['lấy chữ số, bỏ phần 84 khi dán cả số quốc tế', BROWSER_ONLY],
  ['cho gõ dư tối đa 12 số để báo thừa thay vì cắt bớt', BROWSER_ONLY],
  ['9 số sau +84 được thêm số 0, cùng quy tắc với server', BROWSER_ONLY],
  ['cookie ct_staff cũ được chuyển thành ct_session', 'spec bỏ cookie ct_staff'],
]);

function walk(dir, pred) {
  const out = [];
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) out.push(...walk(p, pred));
    else if (pred(p)) out.push(p);
  }
  return out;
}

// Đọc chuỗi ' " hoặc ` bắt đầu tại i. Trả {value, end}, hoặc null nếu là template có ${...}.
function readString(src, i) {
  const q = src[i];
  let v = '';
  for (let j = i + 1; j < src.length; j++) {
    const c = src[j];
    if (c === '\\') {
      const n = src[++j];
      if (n === 'n') v += '\n';
      else if (n === 't') v += '\t';
      else if (n === 'u') {
        v += String.fromCharCode(parseInt(src.slice(j + 1, j + 5), 16));
        j += 4;
      } else v += n;
    } else if (c === q) {
      return { value: v, end: j + 1 };
    } else if (q === '`' && c === '$' && src[j + 1] === '{') {
      return null;
    } else v += c;
  }
  return null;
}

// Đọc một hoặc nhiều chuỗi nối bằng dấu +; trả {name, end} hoặc null.
function readConcat(src, i) {
  let name = '';
  for (;;) {
    const s = readString(src, i);
    if (!s) return null;
    name += s.value;
    i = s.end;
    const plus = /^\s*\+\s*(?=['"`])/.exec(src.slice(i, i + 40));
    if (!plus) return { name, end: i };
    i += plus[0].length;
  }
}

const unparsed = [];
const snippet = (src, at) => src.slice(at, at + 80).replace(/\s+/g, ' ');

function nodeNames(file) {
  const src = readFileSync(file, 'utf8');
  const names = [];
  const re = /(?<![\w.$])(?:it|test)(?:\.\w+)?\s*\(\s*(?=['"`])/g;
  let m;
  while ((m = re.exec(src))) {
    const r = readConcat(src, m.index + m[0].length);
    if (r && /^\s*,/.test(src.slice(r.end, r.end + 20))) names.push(r.name);
    else unparsed.push(`${file}: ${snippet(src, m.index)}`);
  }
  // it(...) mà đối số đầu không phải chuỗi (biến, hàm): in ra, không bỏ qua im lặng.
  const re2 = /(?<![\w.$])(?:it|test)(?:\.\w+)?\s*\(\s*(?!['"`\s])/g;
  while ((m = re2.exec(src))) unparsed.push(`${file}: ${snippet(src, m.index)}`);
  return names;
}

function javaDisplayNames(file) {
  const src = readFileSync(file, 'utf8');
  const names = [];
  const re = /@DisplayName\s*\(\s*(?=")/g;
  let m;
  while ((m = re.exec(src))) {
    const r = readConcat(src, m.index + m[0].length);
    if (r) names.push(r.name);
    else unparsed.push(`${file}: ${snippet(src, m.index)}`);
  }
  return names;
}

const nodeFiles = walk(nodeTestDir, (p) => p.endsWith('.test.js'));
const nodeAll = nodeFiles.flatMap((f) => nodeNames(f).map((n) => ({ n, f })));
const javaFiles = walk(javaTestDir, (p) => p.endsWith('.java'));
const javaNames = new Set(javaFiles.flatMap(javaDisplayNames).filter((n) => !n.startsWith('[java] ')));

// @Disabled còn sót trong src/test.
const disabled = [];
for (const f of javaFiles) {
  readFileSync(f, 'utf8').split('\n').forEach((line, k) => {
    if (/@Disabled\b/.test(line)) disabled.push(`${f}:${k + 1}: ${line.trim()}`);
  });
}

const exempt = nodeAll.filter(({ n }) => EXEMPT.has(n));
const required = nodeAll.filter(({ n }) => !EXEMPT.has(n));
const missing = required.filter(({ n }) => !javaNames.has(n));
const ported = required.length - missing.length;
const exemptNames = [...new Set(exempt.map(({ n }) => n))];
const staleExempt = [...EXEMPT.keys()].filter((k) => !nodeAll.some(({ n }) => n === k));

if (unparsed.length) {
  console.log(`Không đọc được ${unparsed.length} tên test:`);
  unparsed.forEach((u) => console.log('  ' + u));
}
if (disabled.length) {
  console.log(`Còn ${disabled.length} test @Disabled:`);
  disabled.forEach((d) => console.log('  ' + d));
}
if (staleExempt.length) {
  console.log('Tên miễn không còn trong test Node:');
  staleExempt.forEach((n) => console.log('  ' + n));
}
if (missing.length) {
  console.log(`Thiếu ${missing.length} test:`);
  missing.forEach(({ n, f }) => console.log(`  [${f.split(/[\\/]/).pop()}] ${n}`));
}
const why = exemptNames.map((n) => `"${n}": ${EXEMPT.get(n)}`).join('; ');
console.log(`${missing.length ? 'Chưa đủ' : 'Đủ'} ${ported}/${nodeAll.length} (${exempt.length} miễn: ${why})`);
process.exit(missing.length || disabled.length || unparsed.length || staleExempt.length ? 1 : 0);
