import { next, rewrite } from '@vercel/edge';
import { verifyCookie } from './api/_lib/crypto';

export const config = {
  // Run on every path except Vercel internal paths and obvious static noise.
  matcher: '/((?!_vercel/|_next/).*)',
};

const GATE_HEADERS = {
  'Cache-Control': 'no-store, max-age=0',
  'X-Robots-Tag': 'noindex, nofollow',
};

const ALWAYS_ALLOWED_EXACT = new Set<string>([
  '/gate.html',
  '/api/unlock',
  '/favicon.ico',
  '/favicon.svg',
  '/robots.txt',
  '/manifest.json',
]);

function isAlwaysAllowedPath(pathname: string): boolean {
  if (ALWAYS_ALLOWED_EXACT.has(pathname)) return true;
  if (pathname.startsWith('/gate-assets/')) return true;
  return false;
}

function gateRewrite(request: Request): Response {
  const url = new URL(request.url);
  url.pathname = '/gate.html';
  // Preserve url.search so gate.html can capture the original query on load
  // and redirect back to pathname+search after a successful unlock.
  return rewrite(url, { headers: GATE_HEADERS });
}

function misconfigResponse(varName: string): Response {
  return new Response(
    '<!doctype html><title>Service unavailable</title><h1>503 Gate misconfigured</h1>',
    {
      status: 503,
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
        'Vercel-Edge-Misconfig': varName,
      },
    },
  );
}

function parseCookieValue(cookieHeader: string | null, name: string): string | null {
  if (!cookieHeader) return null;
  const parts = cookieHeader.split(';');
  for (const part of parts) {
    const [k, ...rest] = part.trim().split('=');
    if (k === name) return rest.join('=');
  }
  return null;
}

export default async function middleware(request: Request): Promise<Response> {
  // DevNet builds explicitly opt out via NETWORK env var.
  // MainNet Vercel project should set NETWORK=mainnet; DevNet sets anything else.
  const network = process.env.NETWORK;
  if (network && network !== 'mainnet') {
    return next();
  }

  const url = new URL(request.url);
  const pathname = url.pathname;

  if (isAlwaysAllowedPath(pathname)) {
    const res = next();
    if (pathname === '/gate.html') {
      res.headers.set('Cache-Control', GATE_HEADERS['Cache-Control']);
      res.headers.set('X-Robots-Tag', GATE_HEADERS['X-Robots-Tag']);
    }
    return res;
  }

  const secret = process.env.SITE_ACCESS_SECRET;
  const password = process.env.SITE_ACCESS_PASSWORD;
  if (!secret) return misconfigResponse('SITE_ACCESS_SECRET');
  if (!password) return misconfigResponse('SITE_ACCESS_PASSWORD');

  const cookieToken = parseCookieValue(request.headers.get('cookie'), 'hs_access');
  if (cookieToken) {
    const payload = await verifyCookie(cookieToken, secret);
    if (payload) {
      return next();
    }
  }

  return gateRewrite(request);
}
