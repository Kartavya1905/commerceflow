import { useCallback, useEffect, useState } from "react";
import { catalog, describe, orders } from "../api/client";

/** One cart line with what the customer needs to see. Prices are display only: order-service
 * prices the order itself from catalog when it is placed, never from the client. */
export interface CartLine {
  productId: string;
  quantity: number;
  name: string;
  priceMinor: number | null;
  currency: string | null;
}

export function total(lines: CartLine[]): { minor: number; currency: string | null; complete: boolean } {
  const currencies = new Set(lines.map((l) => l.currency).filter((c): c is string => c !== null));
  const complete = lines.every((l) => l.priceMinor !== null) && currencies.size <= 1;
  const minor = lines.reduce((sum, l) => sum + (l.priceMinor ?? 0) * l.quantity, 0);
  return { minor, currency: currencies.size === 1 ? [...currencies][0]! : null, complete };
}

/** The signed-in customer's cart, kept by order-service, with names and prices from catalog. */
export function useCart() {
  const [lines, setLines] = useState<CartLine[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const { data, error } = await orders.GET("/api/v1/cart");
    if (!data) {
      setError(describe(error, "Your cart could not be loaded."));
      return;
    }
    const withProducts = await Promise.all(
      data.items.map(async (item): Promise<CartLine> => {
        const { data: product } = await catalog.GET("/api/v1/products/{productId}", {
          params: { path: { productId: item.productId } },
        });
        return {
          ...item,
          name: product?.name ?? "Unavailable product",
          priceMinor: product?.priceMinor ?? null,
          currency: product?.currency ?? null,
        };
      }),
    );
    setLines(withProducts);
    setError(null);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const setQuantity = useCallback(
    async (productId: string, quantity: number) => {
      const { error, response } =
        quantity > 0
          ? await orders.PUT("/api/v1/cart/items/{productId}", {
              params: { path: { productId } },
              body: { quantity },
            })
          : await orders.DELETE("/api/v1/cart/items/{productId}", {
              params: { path: { productId } },
            });
      if (!response.ok) {
        setError(describe(error, "The cart could not be updated."));
      }
      await load();
    },
    [load],
  );

  const clear = useCallback(async () => {
    await orders.DELETE("/api/v1/cart");
    setLines([]);
  }, []);

  return { lines, error, setQuantity, clear, reload: load };
}
