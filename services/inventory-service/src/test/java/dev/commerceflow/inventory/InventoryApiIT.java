package dev.commerceflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({Containers.Postgres.class, Containers.Kafka.class, TestTokens.class})
class InventoryApiIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  @Test
  void onlyAdminsAdjustStock() throws Exception {
    UUID product = UUID.randomUUID();
    mvc.perform(adjust(product, 10, "RESTOCK", null)).andExpect(status().isUnauthorized());
    mvc.perform(adjust(product, 10, "RESTOCK", TestTokens.customer()))
        .andExpect(status().isForbidden());
    mvc.perform(adjust(product, 10, "RESTOCK", TestTokens.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(10));
  }

  @Test
  void restockCreatesTheRowAndEveryChangeIsRecorded() throws Exception {
    UUID product = UUID.randomUUID();
    mvc.perform(adjust(product, 7, "RESTOCK", TestTokens.admin())).andExpect(status().isOk());
    mvc.perform(adjust(product, -2, "WRITE_OFF", TestTokens.admin()))
        .andExpect(jsonPath("$.available").value(5));

    assertThat(count("select count(*) from stock_adjustments where product_id = ?", product))
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from audit_events where resource_id = ? and action = 'STOCK_ADJUSTED'",
                product.toString()))
        .isEqualTo(2);
  }

  @Test
  void writeOffsCannotTakeAvailableBelowZero() throws Exception {
    UUID product = UUID.randomUUID();
    mvc.perform(adjust(product, 3, "RESTOCK", TestTokens.admin())).andExpect(status().isOk());
    mvc.perform(adjust(product, -4, "WRITE_OFF", TestTokens.admin()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/insufficient-stock"));
    mvc.perform(adjust(UUID.randomUUID(), -1, "WRITE_OFF", TestTokens.admin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsMeaninglessAdjustments() throws Exception {
    UUID product = UUID.randomUUID();
    mvc.perform(adjust(product, -5, "RESTOCK", TestTokens.admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(adjust(product, 5, "WRITE_OFF", TestTokens.admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(adjust(product, 0, "CORRECTION", TestTokens.admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(adjust(product, 1_000_000, "RESTOCK", TestTokens.admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void availabilityIsPublicAndShowsOnlyWhatCanBeBought() throws Exception {
    UUID product = UUID.randomUUID();
    mvc.perform(adjust(product, 4, "RESTOCK", TestTokens.admin())).andExpect(status().isOk());

    mvc.perform(get("/api/v1/inventory/" + product))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(4))
        .andExpect(jsonPath("$.inStock").value(true))
        .andExpect(jsonPath("$.reserved").doesNotExist());
    mvc.perform(get("/api/v1/inventory/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  private static MockHttpServletRequestBuilder adjust(
      UUID product, int delta, String reason, String auth) {
    MockHttpServletRequestBuilder request =
        post("/api/v1/admin/inventory/" + product + "/adjustments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delta\":%d,\"reason\":\"%s\"}".formatted(delta, reason));
    return auth == null ? request : request.header("Authorization", auth);
  }

  private long count(String sql, Object param) {
    return jdbc.sql(sql).param(param).query(Long.class).single();
  }
}
