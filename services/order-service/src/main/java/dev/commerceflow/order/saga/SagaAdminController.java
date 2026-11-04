package dev.commerceflow.order.saga;

import dev.commerceflow.order.saga.SagaQueries.SagaView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin view of saga instances; "stuck" means past its deadline and not finished. */
@RestController
class SagaAdminController {

  private final SagaQueries sagas;

  SagaAdminController(SagaQueries sagas) {
    this.sagas = sagas;
  }

  @GetMapping("/api/v1/admin/sagas")
  Map<String, List<SagaView>> sagas(
      @RequestParam(defaultValue = "false") boolean stuck,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return Map.of("items", sagas.list(stuck, page, size));
  }
}
