import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { catalog, describe } from "../api/client";
import type { components } from "../api/schema/catalog-service";
import { money } from "../lib/format";

type ProductPage = components["schemas"]["ProductPage"];
type Sort = "relevance" | "priceAsc" | "priceDesc" | "newest";
const SIZE = 12;

export function ProductsPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const sort = (params.get("sort") ?? "relevance") as Sort;
  const page = Number(params.get("page") ?? "0");
  const [result, setResult] = useState<ProductPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void catalog
      .GET("/api/v1/products", {
        params: { query: { q: q || undefined, sort, page, size: SIZE } },
      })
      .then(({ data, error }) => {
        if (!current) {
          return;
        }
        setResult(data ?? null);
        setError(data ? null : describe(error, "Products could not be loaded."));
      });
    return () => {
      current = false;
    };
  }, [q, sort, page]);

  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setParams(next);
  };

  return (
    <>
      <h1>Products</h1>
      <form
        className="row"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          update({ q: String(new FormData(event.currentTarget).get("q") ?? ""), page: "" });
        }}
      >
        <input name="q" type="search" defaultValue={q} placeholder="Search" aria-label="Search" />
        <select
          aria-label="Sort"
          value={sort}
          onChange={(event) => update({ sort: event.target.value, page: "" })}
        >
          <option value="relevance">Relevance</option>
          <option value="priceAsc">Price, low to high</option>
          <option value="priceDesc">Price, high to low</option>
          <option value="newest">Newest</option>
        </select>
        <button type="submit">Search</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {result && result.items.length === 0 && <p>No products found.</p>}
      <ul className="grid">
        {result?.items.map((product) => (
          <li key={product.id} className="card">
            <Link to={`/products/${product.id}`}>{product.name}</Link>
            <span className="muted">{product.category}</span>
            <strong>{money(product.priceMinor, product.currency)}</strong>
          </li>
        ))}
      </ul>
      {result && result.totalItems > SIZE && (
        <div className="row">
          <button type="button" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>
            Previous
          </button>
          <span>
            Page {page + 1} of {Math.ceil(result.totalItems / SIZE)}
          </span>
          <button
            type="button"
            disabled={(page + 1) * SIZE >= result.totalItems}
            onClick={() => update({ page: String(page + 1) })}
          >
            Next
          </button>
        </div>
      )}
    </>
  );
}
