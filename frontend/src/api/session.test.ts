import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { authFetch } from "./client";
import { currentToken, refresh, setToken } from "./session";

describe("session", () => {
  it("shares one refresh between concurrent callers (a reused refresh token signs you out)", async () => {
    const calls = fakeApi({
      "POST /api/v1/auth/refresh": () => ({ status: 200, body: { accessToken: "new" } }),
    });
    const results = await Promise.all([refresh(), refresh(), refresh()]);
    expect(results).toEqual(["new", "new", "new"]);
    expect(calls.filter((c) => c.path === "/api/v1/auth/refresh")).toHaveLength(1);
    expect(currentToken()).toBe("new");
  });

  it("forgets the token when the refresh cookie is refused", async () => {
    setToken("old");
    fakeApi({ "POST /api/v1/auth/refresh": () => ({ status: 401 }) });
    expect(await refresh()).toBeNull();
    expect(currentToken()).toBeNull();
  });

  it("retries a call once with a fresh token after a 401", async () => {
    setToken("expired");
    const calls = fakeApi({
      "GET /api/v1/orders": (call) =>
        call.headers.get("Authorization") === "Bearer fresh"
          ? { status: 200, body: { items: [] } }
          : { status: 401 },
      "POST /api/v1/auth/refresh": () => ({ status: 200, body: { accessToken: "fresh" } }),
    });
    const response = await authFetch(new Request("http://localhost/api/v1/orders"));
    expect(response.status).toBe(200);
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      "GET /api/v1/orders",
      "POST /api/v1/auth/refresh",
      "GET /api/v1/orders",
    ]);
  });

  it("does not refresh on a failed sign-in", async () => {
    const calls = fakeApi({ "POST /api/v1/auth/login": () => ({ status: 401 }) });
    const response = await authFetch(
      new Request("http://localhost/api/v1/auth/login", { method: "POST", body: "{}" }),
    );
    expect(response.status).toBe(401);
    expect(calls).toHaveLength(1);
  });

  it("keeps the token out of web storage and page-readable cookies", async () => {
    fakeApi({ "POST /api/v1/auth/refresh": () => ({ status: 200, body: { accessToken: "tkn" } }) });
    await refresh();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain("tkn");
  });
});
