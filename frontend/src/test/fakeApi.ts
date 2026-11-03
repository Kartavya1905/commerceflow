import { vi } from "vitest";

/** One recorded call to the fake API. */
export interface Call {
  method: string;
  path: string;
  headers: Headers;
  body: unknown;
}

type Answer = { status: number; body?: unknown };
type Handler = (call: Call) => Answer | Promise<Answer>;

/**
 * Replaces fetch with routes keyed "METHOD /path" (query ignored). Unrouted calls answer 404, so a
 * test sees exactly the calls it expected. Returns every call made, in order.
 */
export function fakeApi(routes: Record<string, Handler>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init);
      const url = new URL(request.url, "http://localhost");
      const text = await request.text();
      const call: Call = {
        method: request.method,
        path: url.pathname,
        headers: request.headers,
        body: text ? JSON.parse(text) : undefined,
      };
      calls.push(call);
      const handler = routes[`${call.method} ${call.path}`];
      const answer = handler ? await handler(call) : { status: 404 };
      return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
        status: answer.status,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  return calls;
}
