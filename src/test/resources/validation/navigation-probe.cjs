const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Execute the actual production expression, rather than another implementation of the rules.
const probe = fs.readFileSync(process.argv[2], 'utf8');
const template = probe.match(/const expression = `([\s\S]*?)`;/)?.[1];
assert.ok(template, 'Production action expression is present');
const expression = new Function('expected', 'return `' + template + '`;');
let clicked = [];
let elements = [
  { click() { clicked.push('first'); } },
  { click() { clicked.push('second'); } },
];
const context = {
  document: { querySelectorAll: () => elements, querySelector: () => elements[0] },
  location: { hash: '#/classes', pathname: '/p/fixture/' },
};
const check = (kind, value) => vm.runInNewContext(expression({kind, selector:'a[href="#/classes"]', value}), context);
assert.equal(check('ASSERT_EXISTS', null), true, 'Two matching navigation links are allowed');
assert.equal(check('ASSERT_COUNT', '1'), false, 'Explicit exact counts are not relaxed');
assert.equal(check('ASSERT_COUNT', '2'), true);
assert.equal(check('CLICK', null), true);
assert.deepEqual(clicked, ['first'], 'Only the first matching navigation link is clicked');
assert.equal(check('ASSERT_ROUTE', '/classes'), true, 'Hash route is independent of the hosting prefix');
elements = [];
assert.equal(check('ASSERT_EXISTS', null), false, 'An absent navigation link never passes');
assert.equal(check('CLICK', null), false);
assert.equal(check('ASSERT_COUNT', '0'), true, 'Explicit zero-count checks retain their meaning');
