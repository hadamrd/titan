// Uses node:test (built into Node >= 18) so the fixture has zero npm deps.
const test = require('node:test');
const assert = require('node:assert/strict');
const { greet } = require('./index');

test('greet returns a hello string', () => {
  assert.equal(greet('world'), 'hello, world');
});

test('greet rejects empty input', () => {
  assert.throws(() => greet(''), TypeError);
});

test('greet rejects non-string input', () => {
  assert.throws(() => greet(42), TypeError);
});
