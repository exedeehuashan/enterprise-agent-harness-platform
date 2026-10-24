import { vi } from 'vitest';

export interface Call { method: string; path: string; headers: Record<string, string>; body: unknown }

type Handler = (call: Call) => { status?: number; body?: unknown; headers?: Record<string, string> };

/** JSON request bodies as values; anything else (for example a form-encoded token request) as text. */
function parse(text: string): unknown { try { return JSON.parse(text); } catch { return text; } }

/** Replaces fetch with a route table keyed by "METHOD /path" (path without /api/v1 and query). */
export function fakeApi(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const full = String(url).replace(/^\/api\/v1/, '');
    const method = init.method ?? 'GET';
    const call: Call = {
      method, path: full, headers: (init.headers ?? {}) as Record<string, string>,
      body: init.body ? parse(String(init.body)) : undefined,
    };
    calls.push(call);
    const handler = routes[`${method} ${full.split('?')[0]}`];
    const res = handler ? handler(call) : { status: 404, body: { message: `No fake route for ${method} ${full}` } };
    return new Response(res.body === undefined ? '' : JSON.stringify(res.body), {
      status: res.status ?? 200, headers: { 'Content-Type': 'application/json', ...(res.headers ?? {}) },
    });
  }));
  return calls;
}
