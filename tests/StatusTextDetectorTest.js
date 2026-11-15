const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/tw/local/ubertracker/MainActivity.java', 'utf8');
const script = JSON.parse(source.match(/private static final String POLL_SCRIPT = (".*");/)[1]);
new vm.Script(script);

// Minimal browser text-node fixture. The production script runs unchanged.
function element(text, options = {}) {
  return {
    tagName: options.tag || 'DIV', textContent: text,
    childElementCount: options.children || 0,
    parentElement: options.parent || null, hidden: !!options.hidden,
    getClientRects: () => options.noLayout ? [] : [{}],
    getAttribute: name => name === 'aria-hidden' && options.ariaHidden ? 'true' : null,
    hasAttribute: name => name === 'inert' && !!options.inert,
    css: {display: 'block', visibility: 'visible', opacity: '1', ...options.css}
  };
}
function detect(rows) {
  const nodes = rows.flatMap(row => {
    if (typeof row === 'string') row = {text: row};
    const parent = element(row.text, row);
    return (row.parts || [row.text]).map(text => ({nodeValue: text, parentElement: parent}));
  });
  let at = 0;
  return vm.runInNewContext(script, {
    document: {body: {}, createTreeWalker: () => ({nextNode: () => nodes[at++] || null})},
    NodeFilter: {SHOW_TEXT: 4}, getComputedStyle: e => e.css
  });
}
const cases = [
  [['上線中'], 'ONLINE'],
  [['正在途中'], 'TRIP'],
  [['測試員 上線中'], 'ONLINE'],
  [['測試員\n正在途中'], 'TRIP'],
  [['上線中', '測試路100號', 'ABC-0000', 'Uber 隱私權聲明'], 'ONLINE'],
  [['正在途中', '公園', '導航', '測試路38號'], 'TRIP'],
  [['說明：正在途中才會開始計時', '上線中後會停止計時'], 'UNKNOWN'],
  [['路途中'], 'UNKNOWN'],
  [[{text: '上線中', hidden: true}, '正在途中'], 'TRIP'],
  [[{text: '上線中', ariaHidden: true}, '正在途中'], 'TRIP'],
  [[{text: '上線中', noLayout: true}, '正在途中'], 'TRIP'],
  [[{text: '上線中', css: {opacity: '0'}}, '正在途中'], 'TRIP'],
  [[{text: '上線中', parent: element('', {css: {display: 'none'}})}, '正在途中'], 'TRIP'],
  [[{text: '上線中', tag: 'SCRIPT'}], 'UNKNOWN'],
  [[{text: '正在途中', parts: ['正在', '途中'], children: 2}], 'TRIP'],
  [['\u200e上線中\u200f'], 'ONLINE'],
  [['上線中', '正在途中'], 'AMBIGUOUS'],
  [[], 'UNKNOWN']
];
for (const [rows, expected] of cases) assert.equal(detect(rows), expected, JSON.stringify(rows));
console.log(`STATUS TEXT TESTS PASSED: ${cases.length}`);
