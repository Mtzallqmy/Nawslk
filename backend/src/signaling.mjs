import { randomUUID } from 'node:crypto';

/**
 * In-memory signaling session state, intended for a single instance only.
 * This does not relay audio or replace a TURN server.
 */
export class SignalingSessions {
  constructor({ now = Date.now, ttlMs = 120000 } = {}) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.sessions = new Map();
  }
  create(ownerId, peerId) {
    if (!ownerId || !peerId || ownerId === peerId) throw new Error('invalid_participants');
    const id = randomUUID();
    const session = {
      id, ownerId, peerId, state: 'ringing', createdAt: this.now(),
      expiresAt: this.now() + this.ttlMs
    };
    this.sessions.set(id, session);
    return session;
  }
  get(id, participantId) {
    const session = this.sessions.get(id);
    if (!session || session.expiresAt <= this.now()) {
      this.sessions.delete(id);
      return null;
    }
    if (session.ownerId !== participantId && session.peerId !== participantId) return null;
    return session;
  }
  close(id, participantId) {
    const session = this.get(id, participantId);
    if (!session) return false;
    this.sessions.delete(id);
    return true;
  }
  prune() {
    for (const [id, session] of this.sessions) {
      if (session.expiresAt <= this.now()) this.sessions.delete(id);
    }
  }
}
