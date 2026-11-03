import { Link } from "react-router";
import { total, useCart } from "../cart/cart";
import { money } from "../lib/format";

export function CartPage() {
  const { lines, error, setQuantity } = useCart();

  if (!lines) {
    return error ? <p role="alert">{error}</p> : <p aria-busy="true">Loading…</p>;
  }
  if (lines.length === 0) {
    return (
      <>
        <h1>Your cart</h1>
        <p>
          Your cart is empty. <Link to="/">Browse products</Link>
        </p>
      </>
    );
  }
  const sum = total(lines);
  return (
    <>
      <h1>Your cart</h1>
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Price</th>
            <th>Quantity</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.productId}>
              <td>
                <Link to={`/products/${line.productId}`}>{line.name}</Link>
              </td>
              <td>{line.priceMinor !== null && line.currency ? money(line.priceMinor, line.currency) : "—"}</td>
              <td>
                <input
                  type="number"
                  min={0}
                  max={1000}
                  aria-label={`Quantity of ${line.name}`}
                  defaultValue={line.quantity}
                  onBlur={(event) => {
                    const quantity = Number(event.target.value);
                    if (quantity !== line.quantity) {
                      void setQuantity(line.productId, quantity);
                    }
                  }}
                />
              </td>
              <td>
                <button type="button" onClick={() => void setQuantity(line.productId, 0)}>
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {error && <p role="alert">{error}</p>}
      <p>
        Estimated total: <strong>{sum.currency ? money(sum.minor, sum.currency) : "—"}</strong>
        <span className="muted"> (confirmed when you place the order)</span>
      </p>
      <Link className="button" to="/checkout">
        Check out
      </Link>
    </>
  );
}
