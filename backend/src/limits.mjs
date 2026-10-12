// One Railway replica. Deploy a shared limiter before scaling to multiple replicas.
export class RateLimiter {
  constructor({ now = Date.now, max = 120, windowMs = 60000, capacity = 10000 } = {}) {
    Object.assign(this, { now, max, windowMs, capacity }); this.buckets = new Map();
  }
  allow(key) {
    const now = this.now();
    for (const [id,bucket] of this.buckets) if (bucket.until <= now) this.buckets.delete(id);
    let bucket = this.buckets.get(key);
    if (!bucket) {
      if (this.buckets.size >= this.capacity) return false;
      this.buckets.set(key, bucket = { count: 0, until: now + this.windowMs });
    }
    return ++bucket.count <= this.max;
  }
}
