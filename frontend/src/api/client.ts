import createClient from "openapi-fetch";
import type { paths as CatalogPaths } from "./schema/catalog-service";
import type { paths as IdentityPaths } from "./schema/identity-service";
import type { paths as InventoryPaths } from "./schema/inventory-service";
import type { paths as OrderPaths } from "./schema/order-service";
import type { paths as PaymentPaths } from "./schema/payment-service";
import { currentToken, origin, refresh } from "./session";

/**
 * Typed clients generated from the OpenAPI contracts (src/api/schema, from ../contracts). Every
 * call goes to the same origin; the gateway routes it. A 401 on a signed-in call gets one silent
 * refresh and one retry; anything else is the caller's to handle.
 */

function withToken(request: Request, token: string | null): Request {
  const copy = new Request(request);
  if (token) {
    copy.headers.set("Authorization", `Bearer ${token}`);
  }
  return copy;
}

export async function authFetch(request: Request): Promise<Response> {
  const retry = request.clone();
  const first = await fetch(withToken(request, currentToken()));
  // Auth endpoints answer 401 for wrong credentials; refreshing there would only hide that.
  if (first.status !== 401 || new URL(request.url).pathname.startsWith("/api/v1/auth/")) {
    return first;
  }
  const token = await refresh();
  if (!token) {
    return first;
  }
  return fetch(withToken(retry, token));
}

const options = { baseUrl: origin(), fetch: authFetch };

export const catalog = createClient<CatalogPaths>(options);
export const identity = createClient<IdentityPaths>(options);
export const inventory = createClient<InventoryPaths>(options);
export const orders = createClient<OrderPaths>(options);
export const payments = createClient<PaymentPaths>(options);

/** The RFC 9457 problem every service returns on errors. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  correlationId?: string;
}

/** A message to show, with the correlation id support needs (never a stack trace). */
export function describe(problem: Problem | undefined, fallback: string): string {
  if (!problem) {
    return fallback;
  }
  const text = problem.detail ?? problem.title ?? fallback;
  return problem.correlationId ? `${text} (reference ${problem.correlationId})` : text;
}
