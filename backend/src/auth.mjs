import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

export function hashSecret(secret) {
  return createHash('sha256').update(secret, 'utf8').digest('hex');
}
export function secureEqualHash(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !/^[a-f0-9]{64}$/.test(a) || !/^[a-f0-9]{64}$/.test(b)) return false;
  return timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
}
export function newToken() { return randomBytes(32).toString('base64url'); }
export function bearer(header) {
  if (typeof header !== 'string' || !/^Bearer [A-Za-z0-9_-]{20,}$/.test(header)) return null;
  return header.slice(7);
}
export function validOwnerEmail(email) {
  return typeof email === 'string' && email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}
