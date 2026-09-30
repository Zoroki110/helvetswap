import { Ratelimit } from '@upstash/ratelimit';
import { Redis } from '@upstash/redis';

// Lazy-initialised so a missing Upstash env var surfaces as a handled 503
// from the unlock endpoint, not a module-load crash at cold start.
let _ratelimit: Ratelimit | null = null;

export function getRatelimit(): Ratelimit | null {
  if (_ratelimit) return _ratelimit;
  const url = process.env.UPSTASH_REDIS_REST_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN;
  if (!url || !token) return null;
  _ratelimit = new Ratelimit({
    redis: new Redis({ url, token }),
    limiter: Ratelimit.fixedWindow(5, '15 m'),
    analytics: false,
    prefix: 'gate-unlock',
  });
  return _ratelimit;
}

// Resolve client IP with Cloudflare-aware precedence. helvetswap.app is fronted
// by Cloudflare, so cf-connecting-ip is the authoritative client IP; x-forwarded-for
// would otherwise bucket every visitor behind a handful of CF edge IPs and make
// the rate limit effectively global.
export function clientIp(req: Request): string {
  const cf = req.headers.get('cf-connecting-ip');
  if (cf) return cf.trim();
  const xff = req.headers.get('x-forwarded-for');
  if (xff) {
    const first = xff.split(',')[0]?.trim();
    if (first) return first;
  }
  const real = req.headers.get('x-real-ip');
  if (real) return real.trim();
  return 'unknown';
}
