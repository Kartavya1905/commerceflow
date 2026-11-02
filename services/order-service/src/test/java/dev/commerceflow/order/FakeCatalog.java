package dev.commerceflow.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Plays catalog-service's /internal/v1/prices, with switchable failure modes. */
final class FakeCatalog {

  enum Mode {
    NORMAL,
    ERROR,
    SLOW
  }

  record Product(String name, long priceMinor, String currency) {}

  final Map<UUID, Product> products = new ConcurrentHashMap<>();
  final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
  final AtomicInteger calls = new AtomicInteger();
  final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final HttpServer server;

  FakeCatalog() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/internal/v1/prices", this::prices);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  UUID add(String name, long priceMinor) {
    UUID id = UUID.randomUUID();
    products.put(id, new Product(name, priceMinor, "USD"));
    return id;
  }

  private void prices(HttpExchange exchange) throws IOException {
    calls.incrementAndGet();
    lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    switch (mode.get()) {
      case ERROR -> {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
        return;
      }
      case SLOW -> sleep(3_000);
      case NORMAL -> {}
    }
    String query = exchange.getRequestURI().getRawQuery();
    String ids =
        URLDecoder.decode(query.substring(query.indexOf("ids=") + 4), StandardCharsets.UTF_8);
    String json =
        Arrays.stream(ids.split(","))
            .map(UUID::fromString)
            .filter(products::containsKey)
            .map(
                id -> {
                  Product p = products.get(id);
                  return "{\"productId\":\"%s\",\"name\":\"%s\",\"priceMinor\":%d,\"currency\":\"%s\"}"
                      .formatted(id, p.name(), p.priceMinor(), p.currency());
                })
            .collect(Collectors.joining(",", "[", "]"));
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
