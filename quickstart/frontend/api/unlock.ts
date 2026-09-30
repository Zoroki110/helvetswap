import { issueCookie, timingSafeEqualStr } from './_lib/crypto';
import { getRatelimit, clientIp } from './_lib/ratelimit';

export const config = { runtime: 'edge' };

const COOKIE_TTL_SECONDS = 30 * 24 * 60 * 60;

interface UnlockBody {
  password?: unknown;
}

function jsonResponse(status: number, body: unknown, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
      ...extraHeaders,
    },
  });
}

function misconfig(varName: string): Response {
  // Fail closed; surface which env var is missing via header for diagnosis.
  // Response body is generic so end users see nothing useful.
  // eslint-disable-next-line no-console
  console.error(`[gate/unlock] misconfigured: ${varName} is missing`);
  return jsonResponse(503, { ok: false, error: 'gate-misconfigured' }, {
    'Vercel-Edge-Misconfig': varName,
  });
}

export default async function handler(req: Request): Promise<Response> {
  if (req.method !== 'POST') {
    return jsonResponse(405, { ok: false, error: 'method-not-allowed' }, { Allow: 'POST' });
  }

  const password = process.env.SITE_ACCESS_PASSWORD;
  const secret = process.env.SITE_ACCESS_SECRET;
  const upstashUrl = process.env.UPSTASH_REDIS_REST_URL;
  const upstashToken = process.env.UPSTASH_REDIS_REST_TOKEN;

  if (!password) return misconfig('SITE_ACCESS_PASSWORD');
  if (!secret) return misconfig('SITE_ACCESS_SECRET');
  if (!upstashUrl) return misconfig('UPSTASH_REDIS_REST_URL');
  if (!upstashToken) return misconfig('UPSTASH_REDIS_REST_TOKEN');

  const ratelimit = getRatelimit();
  if (!ratelimit) {
    // Defensive: env vars passed the explicit check above, so this is a
    // construction failure. Surface the first Upstash var as the misconfig hint.
    return misconfig('UPSTASH_REDIS_REST_URL');
  }

  const ip = clientIp(req);

  let rl;
  try {
    rl = await ratelimit.limit(ip);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[gate/unlock] ratelimit error', err);
    return jsonResponse(500, { ok: false, error: 'server-error' });
  }

  if (!rl.success) {
    const retryAfterSec = Math.max(1, Math.ceil((rl.reset - Date.now()) / 1000));
    return jsonResponse(429, { ok: false, error: 'rate-limited' }, {
      'Retry-After': String(retryAfterSec),
    });
  }

  let body: UnlockBody;
  try {
    body = (await req.json()) as UnlockBody;
  } catch {
    return jsonResponse(400, { ok: false, error: 'bad-request' });
  }

  const submitted = typeof body.password === 'string' ? body.password : '';

  // Constant-time compare always runs, regardless of submitted length.
  const match = timingSafeEqualStr(submitted, password);

  if (!match) {
    return jsonResponse(401, { ok: false, error: 'invalid-password' });
  }

  const token = await issueCookie(secret, COOKIE_TTL_SECONDS);
  const cookie = [
    `hs_access=${token}`,
    'HttpOnly',
    'Secure',
    'SameSite=Lax',
    'Path=/',
    `Max-Age=${COOKIE_TTL_SECONDS}`,
  ].join('; ');

  return jsonResponse(200, { ok: true }, { 'Set-Cookie': cookie });
}
