import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { catalog, describe, inventory, orders } from "../api/client";
import type { components } from "../api/schema/catalog-service";
import { useAuth } from "../auth/AuthProvider";
import { money } from "../lib/format";

type Product = components["schemas"]["Product"];

export function ProductPage() {
  const { productId = "" } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [product, setProduct] = useState<Product | null>(null);
  const [available, setAvailable] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    void catalog
      .GET("/api/v1/products/{productId}", { params: { path: { productId } } })
      .then(({ data, error }) => {
        setProduct(data ?? null);
        if (!data) {
          setMessage(describe(error, "This product does not exist."));
        }
      });
    void inventory
      .GET("/api/v1/inventory/{productId}", { params: { path: { productId } } })
      .then(({ data }) => setAvailable(data ? data.available : 0));
  }, [productId]);

  async function addToCart() {
    if (!user) {
      navigate("/login", { state: { from: `/products/${productId}` } });
      return;
    }
    const { data: cart } = await orders.GET("/api/v1/cart");
    const already = cart?.items.find((item) => item.productId === productId)?.quantity ?? 0;
    const { error, response } = await orders.PUT("/api/v1/cart/items/{productId}", {
      params: { path: { productId } },
      body: { quantity: already + quantity },
    });
    setMessage(response.ok ? "Added to your cart." : describe(error, "Could not add it to the cart."));
  }

  if (!product) {
    return message ? <p role="alert">{message}</p> : <p aria-busy="true">Loading…</p>;
  }
  return (
    <article className="card">
      <h1>{product.name}</h1>
      <p className="muted">
        {product.category} · SKU {product.sku}
      </p>
      {product.description && <p>{product.description}</p>}
      <p>
        <strong>{money(product.priceMinor, product.currency)}</strong>
      </p>
      <p>{available === null ? "" : available > 0 ? `${available} in stock` : "Out of stock"}</p>
      <div className="row">
        <label>
          Quantity
          <input
            type="number"
            min={1}
            max={1000}
            value={quantity}
            onChange={(event) => setQuantity(Math.max(1, Number(event.target.value)))}
          />
        </label>
        {/* Disabled while the session is still being restored after a reload: "not known yet"
            must not be treated as "signed out" and send a signed-in customer to sign in. */}
        <button
          type="button"
          onClick={() => void addToCart()}
          disabled={available === 0 || user === undefined}
        >
          Add to cart
        </button>
      </div>
      {message && <p role="status">{message}</p>}
    </article>
  );
}
