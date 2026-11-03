import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { catalog, describe, inventory } from "../../api/client";
import type { components } from "../../api/schema/catalog-service";
import type { components as Inv } from "../../api/schema/inventory-service";
import { money } from "../../lib/format";

type Product = components["schemas"]["Product"];
type Stock = Inv["schemas"]["Stock"];

/** Create products, see their stock, restock or write off, archive. Every change is audited. */
export function AdminProductsPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [stock, setStock] = useState<Record<string, Stock | null>>({});
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    const { data } = await catalog.GET("/api/v1/products", {
      params: { query: { sort: "newest", size: 50 } },
    });
    const items = data?.items ?? [];
    setProducts(items);
    const levels = await Promise.all(
      items.map(async (p) => {
        const { data: level } = await inventory.GET("/api/v1/admin/inventory/{productId}", {
          params: { path: { productId: p.id } },
        });
        return [p.id, level ?? null] as const;
      }),
    );
    setStock(Object.fromEntries(levels));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const { error, response } = await catalog.POST("/api/v1/admin/products", {
      body: {
        sku: String(form.get("sku")),
        name: String(form.get("name")),
        category: String(form.get("category")),
        priceMinor: Math.round(Number(form.get("price")) * 100),
        currency: String(form.get("currency")),
      },
    });
    if (response.status === 201) {
      formElement.reset();
      setMessage("Product created. Stock can be added once inventory has registered it.");
      await load();
    } else {
      setMessage(describe(error, "The product was not created."));
    }
  }

  async function adjust(productId: string, delta: number, reason: "RESTOCK" | "WRITE_OFF") {
    const { error, response } = await inventory.POST(
      "/api/v1/admin/inventory/{productId}/adjustments",
      { params: { path: { productId } }, body: { delta, reason } },
    );
    setMessage(response.ok ? "Stock updated." : describe(error, "Stock was not changed."));
    await load();
  }

  async function archive(productId: string) {
    const { error, response } = await catalog.POST("/api/v1/admin/products/{productId}/archive", {
      params: { path: { productId } },
    });
    setMessage(response.ok ? "Product archived." : describe(error, "The product was not archived."));
    await load();
  }

  return (
    <>
      <h1>Products and stock</h1>
      <form className="card stack" onSubmit={create} aria-label="New product">
        <h2>New product</h2>
        <div className="row">
          <label>
            SKU
            <input name="sku" required pattern="[A-Z0-9-]{3,40}" title="Capital letters, digits and dashes" />
          </label>
          <label>
            Name
            <input name="name" required maxLength={200} />
          </label>
          <label>
            Category
            <input name="category" required maxLength={60} />
          </label>
          <label>
            Price
            <input name="price" type="number" min="0.01" step="0.01" required />
          </label>
          <label>
            Currency
            <select name="currency" defaultValue="USD">
              <option>USD</option>
              <option>EUR</option>
              <option>GBP</option>
            </select>
          </label>
          <button type="submit">Create</button>
        </div>
      </form>
      {message && <p role="status">{message}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Product</th>
              <th>Price</th>
              <th>Available</th>
              <th>Reserved</th>
              <th>Sold</th>
              <th>Change stock</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {products.map((p) => {
              const level = stock[p.id];
              return (
                <tr key={p.id}>
                  <td>
                    {p.name}
                    <br />
                    <span className="muted">{p.sku}</span>
                  </td>
                  <td>{money(p.priceMinor, p.currency)}</td>
                  <td>{level?.available ?? "—"}</td>
                  <td>{level?.reserved ?? "—"}</td>
                  <td>{level?.committed ?? "—"}</td>
                  <td>
                    <StockForm onAdjust={(delta, reason) => adjust(p.id, delta, reason)} />
                  </td>
                  <td>
                    <button type="button" onClick={() => void archive(p.id)}>
                      Archive
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

function StockForm({
  onAdjust,
}: {
  onAdjust: (delta: number, reason: "RESTOCK" | "WRITE_OFF") => Promise<void>;
}) {
  const [amount, setAmount] = useState(10);
  return (
    <div className="row">
      <input
        type="number"
        min={1}
        max={100000}
        value={amount}
        aria-label="Units"
        onChange={(event) => setAmount(Math.max(1, Number(event.target.value)))}
      />
      <button type="button" onClick={() => void onAdjust(amount, "RESTOCK")}>
        Add
      </button>
      <button type="button" onClick={() => void onAdjust(-amount, "WRITE_OFF")}>
        Write off
      </button>
    </div>
  );
}
