import { vi } from 'vitest';

export type Call = { method: string; url: string; init: RequestInit };
type Handler = (call: Call) => Response | Promise<Response>;

/**
 * Stubs global fetch with a route table keyed "METHOD /path". An array of handlers answers in order
 * and repeats its last entry. Unknown routes reject, so an unexpected call fails the test loudly.
 */
export function fakeApi(routes: Record<string, Handler | Handler[]>) {
  const calls: Call[] = [];
  const queues = new Map(Object.entries(routes).map(([key, value]) => [key, Array.isArray(value) ? [...value] : [value]]));
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname : new URL(input.url).pathname;
      const call = { method: (init.method ?? 'GET').toUpperCase(), url, init };
      calls.push(call);
      const queue = queues.get(`${call.method} ${call.url}`);
      if (!queue) {
        throw new Error(`Unexpected request ${call.method} ${call.url}`);
      }
      const handler = queue.length > 1 ? queue.shift()! : queue[0];
      return handler(call);
    }),
  );
  return { calls };
}

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export const problem = (status: number, type = 'about:blank') =>
  new Response(JSON.stringify({ type, status }), { status, headers: { 'Content-Type': 'application/problem+json' } });

export const tokenResponse = (accessToken = 'access-1', expiresIn = 900) =>
  json(200, { accessToken, tokenType: 'Bearer', expiresIn });

export const authHeader = (call: Call) => new Headers(call.init.headers).get('Authorization');
