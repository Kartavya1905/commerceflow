import { NavLink, Navigate, Outlet, Route, Routes, useLocation } from "react-router";
import type { ReactNode } from "react";
import { useAuth } from "./auth/AuthProvider";
import { AdminDeadLettersPage } from "./pages/admin/AdminDeadLettersPage";
import { AdminOrdersPage } from "./pages/admin/AdminOrdersPage";
import { AdminPaymentsPage } from "./pages/admin/AdminPaymentsPage";
import { AdminProductsPage } from "./pages/admin/AdminProductsPage";
import { AdminSagasPage } from "./pages/admin/AdminSagasPage";
import { CartPage } from "./pages/CartPage";
import { CheckoutPage } from "./pages/CheckoutPage";
import { LoginPage, RegisterPage } from "./pages/AuthPages";
import { OrderPage } from "./pages/OrderPage";
import { OrdersPage } from "./pages/OrdersPage";
import { ProductPage } from "./pages/ProductPage";
import { ProductsPage } from "./pages/ProductsPage";

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<ProductsPage />} />
        <Route path="products/:productId" element={<ProductPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route element={<RequireUser />}>
          <Route path="cart" element={<CartPage />} />
          <Route path="checkout" element={<CheckoutPage />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="orders/:orderId" element={<OrderPage />} />
        </Route>
        <Route path="admin" element={<RequireAdmin />}>
          <Route index element={<Navigate to="orders" replace />} />
          <Route path="orders" element={<AdminOrdersPage />} />
          <Route path="products" element={<AdminProductsPage />} />
          <Route path="sagas" element={<AdminSagasPage />} />
          <Route path="payments" element={<AdminPaymentsPage />} />
          <Route path="dead-letters" element={<AdminDeadLettersPage />} />
        </Route>
        <Route path="*" element={<p>Page not found.</p>} />
      </Route>
    </Routes>
  );
}

function Layout() {
  const { user, logout } = useAuth();
  return (
    <>
      <header className="bar">
        <NavLink to="/" className="brand">
          CommerceFlow
        </NavLink>
        <nav aria-label="Main">
          <NavLink to="/">Products</NavLink>
          {user && <NavLink to="/cart">Cart</NavLink>}
          {user && <NavLink to="/orders">Orders</NavLink>}
          {user?.role === "ADMIN" && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        <div className="who">
          {user === undefined ? null : user ? (
            <>
              <span>{user.displayName}</span>
              <button type="button" onClick={() => void logout()}>
                Sign out
              </button>
            </>
          ) : (
            <>
              <NavLink to="/login">Sign in</NavLink>
              <NavLink to="/register">Register</NavLink>
            </>
          )}
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}

function Waiting({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  return user === undefined ? <p aria-busy="true">Loading…</p> : <>{children}</>;
}

function RequireUser() {
  const { user } = useAuth();
  const location = useLocation();
  return (
    <Waiting>
      {user ? <Outlet /> : <Navigate to="/login" replace state={{ from: location.pathname }} />}
    </Waiting>
  );
}

/**
 * Hides the admin pages from everyone else. This is convenience, not security: the API refuses
 * admin calls from customers with 403 whatever the UI shows.
 */
function RequireAdmin() {
  const { user } = useAuth();
  return (
    <Waiting>
      {user?.role === "ADMIN" ? (
        <section className="admin">
          <nav aria-label="Admin" className="subnav">
            <NavLink to="orders">Orders</NavLink>
            <NavLink to="products">Products and stock</NavLink>
            <NavLink to="sagas">Sagas</NavLink>
            <NavLink to="payments">Payments</NavLink>
            <NavLink to="dead-letters">Dead letters</NavLink>
          </nav>
          <Outlet />
        </section>
      ) : user ? (
        <p role="alert">This page is for administrators.</p>
      ) : (
        <Navigate to="/login" replace />
      )}
    </Waiting>
  );
}
