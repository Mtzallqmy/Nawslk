import test from 'node:test';
import assert from 'node:assert/strict';
import { appendSignal } from '../src/signaling-store.mjs';

test('rejects invalid signaling message kinds', async () => {
  await assert.rejects(appendSignal({}, {id:'session'}, 'caller', 'delete', {}), /invalid_signal_kind/);
});
test('rejects oversized signaling payloads', async () => {
  await assert.rejects(appendSignal({}, {id:'session'}, 'caller', 'offer', {sdp:'x'.repeat(16001)}), /signal_too_large/);
});
