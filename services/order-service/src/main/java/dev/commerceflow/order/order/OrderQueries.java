package dev.commerceflow.order.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side. Every customer-facing query takes the customer id from the token and puts it in the
 * WHERE clause, so another customer's order is indistinguishable from one that does not exist.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueries {

  public record Page(List<OrderView> items, int page, int size, long totalItems) {}

  private static final String COLUMNS =
      "id, status, failure_reason, total_minor, currency, created_at, updated_at";

  private final JdbcClient jdbc;

  public OrderQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Internal use only: no owner check. */
  public Optional<OrderView> find(UUID orderId) {
    return jdbc.sql("select " + COLUMNS + " from orders where id = :id")
        .param("id", orderId)
        .query(this::map)
        .optional();
  }

  public Optional<OrderView> findForCustomer(UUID orderId, UUID customerId) {
    return jdbc.sql("select " + COLUMNS + " from orders where id = :id and customer_id = :c")
        .param("id", orderId)
        .param("c", customerId)
        .query(this::map)
        .optional();
  }

  public Page forCustomer(UUID customerId, int page, int size) {
    List<OrderView> items =
        jdbc.sql(
                "select "
                    + COLUMNS
                    + " from orders where customer_id = :c"
                    + " order by created_at desc, id limit :limit offset :offset")
            .param("c", customerId)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(this::map)
            .list();
    long total =
        jdbc.sql("select count(*) from orders where customer_id = :c")
            .param("c", customerId)
            .query(Long.class)
            .single();
    return new Page(items, page, size, total);
  }

  /** Admin view across all customers, optionally by status. */
  public Page all(String status, int page, int size) {
    String where = " where (cast(:status as text) is null or status = :status)";
    List<OrderView> items =
        jdbc.sql(
                "select "
                    + COLUMNS
                    + " from orders"
                    + where
                    + " order by created_at desc, id limit :limit offset :offset")
            .param("status", status)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(this::map)
            .list();
    long total =
        jdbc.sql("select count(*) from orders" + where)
            .param("status", status)
            .query(Long.class)
            .single();
    return new Page(items, page, size, total);
  }

  private OrderView map(ResultSet rs, int row) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    return new OrderView(
        id,
        rs.getString("status"),
        rs.getString("failure_reason"),
        items(id),
        rs.getLong("total_minor"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  private List<OrderView.Item> items(UUID orderId) {
    return jdbc.sql(
            "select product_id, product_name, quantity, unit_price_minor from order_items"
                + " where order_id = :id order by product_id")
        .param("id", orderId)
        .query(
            (rs, n) ->
                new OrderView.Item(
                    rs.getObject("product_id", UUID.class),
                    rs.getString("product_name"),
                    rs.getInt("quantity"),
                    rs.getLong("unit_price_minor")))
        .list();
  }
}
