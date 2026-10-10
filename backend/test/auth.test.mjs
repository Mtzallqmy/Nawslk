import test from 'node:test';
import assert from 'node:assert/strict';
import { hashSecret, secureEqualHash, newToken, bearer, validOwnerEmail } from '../src/auth.mjs';

test('tokens are unique and strong', () => {
  const a = newToken(), b = newToken();
  assert.notEqual(a, b);
  assert.ok(a.length >= 40);
});
test('hash comparisons reject mismatches', () => {
  assert.ok(secureEqualHash(hashSecret('abc'), hashSecret('abc')));
  assert.equal(secureEqualHash(hashSecret('abc'), hashSecret('abd')), false);
  assert.equal(secureEqualHash('bad', hashSecret('abc')), false);
});
test('bearer token parser is strict', () => {
  assert.equal(bearer('Bearer abc'), null);
  assert.equal(bearer('Basic '+newToken()), null);
  const t = newToken();
  assert.equal(bearer('Bearer '+t), t);
});
test('owner email validation', () => {
  assert.ok(validOwnerEmail('owner@example.com'));
  assert.equal(validOwnerEmail('x'), false);
});
