package dev.commerceflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.inventory.stock.StockService;
import dev.commerceflow.inventory.stock.StockService.Reason;
import dev.commerceflow.inventory.stock.StockService.Stock;
import dev.commerceflow.testsupport.TestTokens;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Helpers shared by the inventory tests. Each test creates its own products. */
final class InventoryFixtures {

  private InventoryFixtures() {}

  static UUID productWithStock(StockService stock, int units) {
    UUID productId = UUID.randomUUID();
    stock.adjust(productId, units, Reason.RESTOCK, "test fixture", TestTokens.ADMIN_ID);
    return productId;
  }

  static Stock stockOf(StockService stock, UUID productId) {
    return stock.find(productId).orElseThrow();
  }

  /** INV-1 and INV-2 over the whole table, checked after every test. */
  static void assertInvariantsHold(JdbcClient jdbc) {
    long broken =
        jdbc.sql(
                """
                select count(*) from inventory
                 where available < 0 or reserved < 0 or committed < 0
                    or available + reserved + committed <> total_received - total_written_off
                """)
            .query(Long.class)
            .single();
    assertThat(broken).as("rows breaking INV-1 or INV-2").isZero();

    long orphanReserved =
        jdbc.sql(
                """
                select count(*) from inventory i
                 where i.reserved <> coalesce((
                       select sum(l.quantity) from reservation_lines l
                         join order_reservations r on r.order_id = l.order_id
                        where l.product_id = i.product_id and r.status = 'RESERVED'), 0)
                """)
            .query(Long.class)
            .single();
    assertThat(orphanReserved).as("reserved counts that do not match open reservations").isZero();
  }
}
