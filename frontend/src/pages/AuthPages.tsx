import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useAuth } from "../auth/AuthProvider";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from ?? "/";
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    const problem = await login(String(form.get("email")), String(form.get("password")));
    setBusy(false);
    if (problem) {
      setError(problem);
    } else {
      navigate(from, { replace: true });
    }
  }

  return (
    <form className="card narrow" onSubmit={submit}>
      <h1>Sign in</h1>
      <label>
        Email
        <input name="email" type="email" autoComplete="username" required />
      </label>
      <label>
        Password
        <input name="password" type="password" autoComplete="current-password" required />
      </label>
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={busy}>
        Sign in
      </button>
      <p>
        New here? <Link to="/register">Create an account</Link>
      </p>
    </form>
  );
}

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    const problem = await register(
      String(form.get("email")),
      String(form.get("password")),
      String(form.get("displayName")),
    );
    setBusy(false);
    if (problem) {
      setError(problem);
    } else {
      navigate("/", { replace: true });
    }
  }

  return (
    <form className="card narrow" onSubmit={submit}>
      <h1>Create an account</h1>
      <label>
        Name
        <input name="displayName" autoComplete="name" required maxLength={100} />
      </label>
      <label>
        Email
        <input name="email" type="email" autoComplete="username" required />
      </label>
      <label>
        Password
        <input
          name="password"
          type="password"
          autoComplete="new-password"
          required
          minLength={12}
        />
      </label>
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={busy}>
        Create account
      </button>
    </form>
  );
}
