package dev.commerceflow.payment.provider;

import dev.commerceflow.payment.config.PaymentConfig;
import dev.commerceflow.payment.config.PaymentConfig.PaymentProperties;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * The card provider (payment-sandbox locally). Every call is refused inside a database transaction,
 * and every charge is keyed by the attempt id, so a retry can never charge twice.
 */
@Component
public class ProviderClient {

  private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

  /** What a charge request established. Only {@link Unknown} leaves the question open. */
  public sealed interface ChargeOutcome {
    record Succeeded(String chargeId) implements ChargeOutcome {}

    /** The provider answered no: card_declined, insufficient_funds or invalid_payment_token. */
    record Declined(String code) implements ChargeOutcome {}

    /**
     * No answer (PROVIDER_TIMEOUT) or an unusable one (PROVIDER_ERROR). The charge may have
     * happened: never treat this as a decline.
     */
    record Unknown(String reason) implements ChargeOutcome {}
  }

  /** A charge as the provider records it. */
  public record ProviderCharge(String id, String status, String declineCode) {
    public boolean succeeded() {
      return "SUCCEEDED".equals(status);
    }
  }

  /** The provider could not be asked, or did not answer usably. Safe to retry. */
  public static class ProviderUnavailableException extends RuntimeException {
    ProviderUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record ChargeList(List<ProviderCharge> data) {}

  private record RefundBody(String id) {}

  private final RestClient client;
  private final CircuitBreaker breaker;

  public ProviderClient(PaymentProperties properties, CircuitBreakerFactory<?, ?> breakers) {
    if (properties.providerApiKey() == null || properties.providerApiKey().isBlank()) {
      throw new IllegalStateException("SANDBOX_API_KEY (the provider key) must be set");
    }
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.providerConnectTimeout()).build());
    requests.setReadTimeout(properties.providerReadTimeout());
    this.client =
        RestClient.builder()
            .baseUrl(properties.providerUri())
            .requestFactory(requests)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.providerApiKey())
            .build();
    this.breaker = breakers.create(PaymentConfig.PROVIDER_BREAKER);
  }

  /** Charges once per attempt id, however often it is called. */
  public ChargeOutcome charge(UUID attemptId, long amountMinor, String currency, String token) {
    requireNoTransaction();
    return breaker.run(
        () ->
            client
                .post()
                .uri("/v1/charges")
                .header("Idempotency-Key", attemptId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    Map.of("amountMinor", amountMinor, "currency", currency, "paymentToken", token))
                .exchange(
                    (request, response) -> {
                      int status = response.getStatusCode().value();
                      if (status == 200 || status == 402) {
                        ProviderCharge charge = response.bodyTo(ProviderCharge.class);
                        if (charge == null) {
                          throw new IllegalStateException("charge answered " + status + " empty");
                        }
                        return status == 200
                            ? new ChargeOutcome.Succeeded(charge.id())
                            : new ChargeOutcome.Declined(charge.declineCode());
                      }
                      if (status == 400) {
                        // Rejected before charging (the token is not one the provider knows).
                        return new ChargeOutcome.Declined("invalid_payment_token");
                      }
                      throw new IllegalStateException("charge answered " + status);
                    }),
        failure -> {
          String reason = isTimeout(failure) ? "PROVIDER_TIMEOUT" : "PROVIDER_ERROR";
          log.warn(
              "Charge for attempt {} unresolved ({}): {}", attemptId, reason, failure.toString());
          return new ChargeOutcome.Unknown(reason);
        });
  }

  /** The charge made under this attempt's key, if the provider has one. */
  public Optional<ProviderCharge> findCharge(UUID attemptId) {
    requireNoTransaction();
    ChargeList found =
        breaker.run(
            () ->
                client
                    .get()
                    .uri(
                        uri ->
                            uri.path("/v1/charges")
                                .queryParam("idempotency_key", attemptId)
                                .build())
                    .retrieve()
                    .body(ChargeList.class),
            failure -> {
              throw new ProviderUnavailableException("charge lookup failed", failure);
            });
    return found == null || found.data() == null
        ? Optional.empty()
        : found.data().stream().findFirst();
  }

  /**
   * Refunds the whole charge, once per attempt however often it is called.
   *
   * @return the provider's refund id
   */
  public String refund(UUID attemptId, String chargeId, long amountMinor) {
    requireNoTransaction();
    RefundBody refund =
        breaker.run(
            () ->
                client
                    .post()
                    .uri("/v1/refunds")
                    .header("Idempotency-Key", "refund-" + attemptId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chargeId", chargeId, "amountMinor", amountMinor))
                    .retrieve()
                    .body(RefundBody.class),
            failure -> {
              throw new ProviderUnavailableException("refund failed", failure);
            });
    if (refund == null || refund.id() == null) {
      throw new ProviderUnavailableException("refund answered without an id", null);
    }
    return refund.id();
  }

  /**
   * Holding a transaction across a provider call ties a pooled connection, and any row locks, to
   * the provider's latency: a slow provider then exhausts the pool for everything else.
   */
  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("payment provider called inside a database transaction");
    }
  }

  private static boolean isTimeout(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof HttpTimeoutException
          || t instanceof SocketTimeoutException
          || t instanceof TimeoutException) {
        return true;
      }
    }
    return false;
  }
}
