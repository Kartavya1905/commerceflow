package dev.commerceflow.systemtests;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The running Compose stack, as the tests see it from outside: its HTTP APIs, its containers
 * (through the docker CLI) and Toxiproxy. Secrets come from the git-ignored .env, as the scripts
 * read them; nothing is printed.
 */
final class Stack {

  static final String PROJECT = "commerceflow";
  static final String GATEWAY = "http://localhost:8080";

  /** Orders go straight to order-service: the gateway's per-IP rate limit is not under test. */
  static final String ORDER = "http://localhost:8083";

  static final String PAYMENT = "http://localhost:8085";
  static final String TOXIPROXY = "http://localhost:8474";

  final Path root;
  final Map<String, String> env;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final JsonMapper json = JsonMapper.builder().build();

  Stack() {
    String configured = System.getProperty("commerceflow.root");
    root = Path.of(configured != null ? configured : "..").toAbsolutePath().normalize();
    env = readEnv(root.resolve(".env"));
  }

  private static Map<String, String> readEnv(Path file) {
    Map<String, String> values = new HashMap<>();
    try {
      for (String line : Files.readAllLines(file)) {
        int eq = line.indexOf('=');
        if (!line.startsWith("#") && eq > 0) {
          values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("no .env: start the stack with scripts/dev-up.sh first", e);
    }
    return values;
  }

  // --- HTTP ----------------------------------------------------------------------------------

  record Reply(int status, JsonNode body) {}

  Reply call(String method, String url, String token, String body, Map<String, String> headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    headers.forEach(request::header);
    if (body != null) {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      String text = response.body();
      JsonNode parsed =
          text == null || text.isBlank() || !text.trim().startsWith("{")
              ? json.createObjectNode()
              : json.readTree(text);
      return new Reply(response.statusCode(), parsed);
    } catch (IOException e) {
      // A refused or cut connection: what a client sees during an outage.
      return new Reply(-1, json.createObjectNode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  Reply get(String url, String token) {
    return call("GET", url, token, null, Map.of());
  }

  Reply post(String url, String token, String body) {
    return call("POST", url, token, body, Map.of());
  }

  String login(String email, String password) {
    Reply reply =
        call(
            "POST",
            GATEWAY + "/api/v1/auth/login",
            null,
            json.writeValueAsString(Map.of("email", email, "password", password)),
            Map.of("X-Token-Delivery", "body"));
    if (reply.status() != 200) {
      throw new IllegalStateException("login failed with " + reply.status());
    }
    return reply.body().path("accessToken").asString();
  }

  String adminToken() {
    return login(env.get("BOOTSTRAP_ADMIN_EMAIL"), env.get("BOOTSTRAP_ADMIN_PASSWORD"));
  }

  /** A new customer for this run; one is enough, since orders are told apart by their keys. */
  String newCustomer() {
    String email = "system-test-" + UUID.randomUUID() + "@example.com";
    String password = "system-test-" + UUID.randomUUID();
    Reply registered =
        post(
            GATEWAY + "/api/v1/auth/register",
            null,
            json.writeValueAsString(
                Map.of("email", email, "password", password, "displayName", "System Test")));
    if (registered.status() != 201) {
      throw new IllegalStateException("register failed with " + registered.status());
    }
    return login(email, password);
  }

  /** A product with this much stock, once inventory has heard of it. */
  UUID product(String admin, long priceMinor, int stock) {
    String sku = "SYS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    Reply created =
        post(
            GATEWAY + "/api/v1/admin/products",
            admin,
            json.writeValueAsString(
                Map.of(
                    "sku",
                    sku,
                    "name",
                    "system test " + sku,
                    "category",
                    "system-test",
                    "priceMinor",
                    priceMinor,
                    "currency",
                    "USD")));
    if (created.status() != 201) {
      throw new IllegalStateException("product creation failed with " + created.status());
    }
    UUID id = UUID.fromString(created.body().path("id").asString());
    String adjustment = json.writeValueAsString(Map.of("delta", stock, "reason", "RESTOCK"));
    // Inventory learns of the product from ProductCreated, a moment later.
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .until(
            () ->
                post(GATEWAY + "/api/v1/admin/inventory/" + id + "/adjustments", admin, adjustment)
                        .status()
                    == 200);
    return id;
  }

  /**
   * @return the order id, or null if it was not accepted (with the status in {@code refused})
   */
  UUID placeOrder(String customer, UUID product, int quantity, String paymentToken) {
    Reply reply = tryPlaceOrder(customer, product, quantity, paymentToken);
    if (reply.status() != 202) {
      throw new IllegalStateException("order was not accepted: " + reply.status());
    }
    return UUID.fromString(reply.body().path("id").asString());
  }

  Reply tryPlaceOrder(String customer, UUID product, int quantity, String paymentToken) {
    String body =
        json.writeValueAsString(
            Map.of(
                "items",
                List.of(Map.of("productId", product, "quantity", quantity)),
                "paymentToken",
                paymentToken));
    return call(
        "POST",
        ORDER + "/api/v1/orders",
        customer,
        body,
        Map.of("Idempotency-Key", "system-test-" + UUID.randomUUID()));
  }

  /** One counter (or gauge) from a service's Prometheus endpoint, summed over its series. */
  double metric(String serviceUrl, String name, String labelFilter) {
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(serviceUrl + "/actuator/prometheus"))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      double sum = 0;
      for (String line : response.body().split("\n")) {
        if (line.startsWith(name + "{") || line.startsWith(name + " ")) {
          if (labelFilter == null || line.contains(labelFilter)) {
            sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
          }
        }
      }
      return sum;
    } catch (IOException e) {
      return Double.NaN;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // --- Containers ----------------------------------------------------------------------------

  static String container(String service) {
    return PROJECT + "-" + service + "-1";
  }

  String docker(String... args) {
    return run(Map.of(), prepend("docker", args));
  }

  /** docker compose with the chaos override, as scripts/dev-up.sh --chaos runs it. */
  String compose(Map<String, String> extraEnv, String... args) {
    List<String> command = new ArrayList<>();
    command.addAll(
        List.of(
            "docker",
            "compose",
            "-f",
            root.resolve("deploy/compose/docker-compose.yml").toString(),
            "-f",
            root.resolve("deploy/compose/chaos.yml").toString(),
            "--env-file",
            root.resolve(".env").toString(),
            "--profile",
            "apps"));
    command.addAll(List.of(args));
    return run(extraEnv, command.toArray(String[]::new));
  }

  private static String[] prepend(String first, String[] rest) {
    String[] all = new String[rest.length + 1];
    all[0] = first;
    System.arraycopy(rest, 0, all, 1, rest.length);
    return all;
  }

  private String run(Map<String, String> extraEnv, String... command) {
    return execute(extraEnv, null, command);
  }

  private String runWithInput(String input, String... command) {
    return execute(Map.of(), input, command);
  }

  private String execute(Map<String, String> extraEnv, String input, String... command) {
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(extraEnv);
      Process process = builder.start();
      try (var stdin = process.getOutputStream()) {
        if (input != null) {
          stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
        // Never echo a credential, whatever a future caller passes.
        String shown = String.join(" ", command).replaceAll("Bearer \\S+", "Bearer ***");
        throw new IllegalStateException(shown + " failed:\n" + output);
      }
      return output.trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  boolean healthy(String service) {
    try {
      return "healthy"
          .equals(docker("inspect", "-f", "{{.State.Health.Status}}", container(service)));
    } catch (IllegalStateException e) {
      return false;
    }
  }

  String startedAt(String service) {
    return docker("inspect", "-f", "{{.State.StartedAt}}", container(service));
  }

  void awaitHealthy(String... services) {
    await()
        .atMost(Duration.ofMinutes(3))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> List.of(services).stream().allMatch(this::healthy));
  }

  /**
   * The sandbox is reachable only inside the Compose network, so the switch is flipped there. The
   * command goes in on stdin and reads the key from the container's own environment: no secret on a
   * command line, and no JSON quoting for Windows to mangle on the way.
   */
  void sandboxFault(String mode) {
    if (!mode.matches("[A-Z_]+")) {
      throw new IllegalArgumentException("fault mode " + mode);
    }
    String script =
        """
        curl -fsS -X POST -H "Authorization: Bearer $SANDBOX_API_KEY" \
          -H 'Content-Type: application/json' -d '{"mode":"%s"}' \
          http://localhost:8099/sandbox/faults
        """
            .formatted(mode);
    runWithInput(script, "docker", "exec", "-i", container("payment-sandbox"), "sh", "-s");
  }

  void orderDatabaseLink(boolean enabled) {
    Reply reply = post(TOXIPROXY + "/proxies/order_db", null, "{\"enabled\":" + enabled + "}");
    if (reply.status() != 200) {
      throw new IllegalStateException("toxiproxy answered " + reply.status());
    }
  }

  boolean toxiproxyRunning() {
    return get(TOXIPROXY + "/version", null).status() == 200;
  }
}
