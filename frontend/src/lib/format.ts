/** Minor units to a display price, for example 3998 USD to "$39.98". */
export function money(minor: number, currency: string): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(minor / 100);
}

export function when(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "";
}

export const TERMINAL = new Set(["CONFIRMED", "FAILED"]);

/** What the customer sees for each status: the saga's internals stay internal. */
export function statusLabel(status: string): string {
  switch (status) {
    case "PENDING":
      return "Reserving stock";
    case "PAYMENT_PROCESSING":
      return "Processing payment";
    case "CONFIRMED":
      return "Confirmed";
    case "FAILED":
      return "Failed";
    default:
      return status;
  }
}

export function failureLabel(reason: string | undefined): string {
  switch (reason) {
    case "OUT_OF_STOCK":
      return "Out of stock";
    case "PAYMENT_DECLINED":
      return "Payment declined";
    case "PAYMENT_TIMEOUT":
      return "Payment could not be confirmed; any charge was refunded";
    case "PRICE_UNAVAILABLE":
      return "Prices could not be confirmed";
    case "INTERNAL_ERROR":
      return "Something went wrong; any charge was refunded";
    default:
      return "";
  }
}
