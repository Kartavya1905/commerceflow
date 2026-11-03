import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthProvider";
import { fakeApi } from "./test/fakeApi";

function signedInAs(role: "CUSTOMER" | "ADMIN") {
  return fakeApi({
    "POST /api/v1/auth/refresh": () => ({ status: 200, body: { accessToken: "t" } }),
    "GET /api/v1/users/me": () => ({
      status: 200,
      body: { id: "u", email: "a@example.com", displayName: "Ada", role },
    }),
    "GET /api/v1/products": () => ({ status: 200, body: { items: [], page: 0, size: 12, totalItems: 0 } }),
    "GET /api/v1/admin/orders": () => ({ status: 200, body: { items: [], page: 0, size: 50, totalItems: 0 } }),
  });
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("admin pages", () => {
  it("are not offered to a customer, and the admin URL says so instead of calling the API", async () => {
    const calls = signedInAs("CUSTOMER");
    renderAt("/admin/orders");
    expect(await screen.findByRole("alert")).toHaveTextContent("for administrators");
    expect(screen.queryByRole("link", { name: "Admin" })).toBeNull();
    expect(calls.some((c) => c.path.startsWith("/api/v1/admin/"))).toBe(false);
  });

  it("are offered to an admin", async () => {
    signedInAs("ADMIN");
    renderAt("/admin/orders");
    expect(await screen.findByRole("heading", { name: "Orders" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Admin" })).toBeInTheDocument();
  });

  it("send a signed-out visitor to sign in", async () => {
    fakeApi({ "POST /api/v1/auth/refresh": () => ({ status: 401 }) });
    renderAt("/admin/orders");
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  });
});
