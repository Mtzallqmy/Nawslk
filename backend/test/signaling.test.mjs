import test from 'node:test';
import assert from 'node:assert/strict';
import { SignalingSessions } from '../src/signaling.mjs';

test('signaling session is scoped to its participants', () => {
  const sessions = new SignalingSessions();
  const session = sessions.create('alice', 'bob');
  assert.equal(sessions.get(session.id, 'alice')?.id, session.id);
  assert.equal(sessions.get(session.id, 'bob')?.id, session.id);
  assert.equal(sessions.get(session.id, 'eve'), null);
  assert.equal(sessions.close(session.id, 'eve'), false);
  assert.equal(sessions.close(session.id, 'alice'), true);
  assert.equal(sessions.get(session.id, 'bob'), null);
});
test('expired sessions cannot be reused', () => {
  let now = 10;
  const sessions = new SignalingSessions({ now: () => now, ttlMs: 100 });
  const session = sessions.create('a', 'b');
  now = 111;
  assert.equal(sessions.get(session.id, 'a'), null);
});
test('invalid participants rejected', () => {
  const sessions = new SignalingSessions();
  assert.throws(() => sessions.create('a', 'a'));
});
