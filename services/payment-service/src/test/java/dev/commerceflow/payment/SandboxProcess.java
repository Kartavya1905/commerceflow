package dev.commerceflow.payment;

import dev.commerceflow.testsupport.Containers;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real payment-sandbox, run from its built jar as a separate process with its own database: the
 * same isolation as in Compose, so a charge it records survives anything payment-service does. One
 * per test JVM.
 */
public final class SandboxProcess {

  public static final String API_KEY = "test-sandbox-key-0123456789";

  /** How long tok_timeout holds its response: longer than payment-service's test read timeout. */
  static final Duration STALL = Duration.ofSeconds(3);

  private static SandboxProcess instance;

  public final String baseUrl;
  final JdbcClient db;
  private final HttpClient http = HttpClient.newHttpClient();

  private SandboxProcess(String baseUrl, JdbcClient db) {
    this.baseUrl = baseUrl;
    this.db = db;
  }

  public static synchronized SandboxProcess get() {
    if (instance == null) {
      instance = start();
    }
    return instance;
  }

  /** Number of charges the provider holds under this idempotency key. */
  public long charges(Object idempotencyKey) {
    return db.sql("select count(*) from charges where idempotency_key = ?")
        .param(idempotencyKey.toString())
        .query(Long.class)
        .single();
  }

  public long refundedMinor(Object idempotencyKey) {
    return db.sql("select coalesce(sum(refunded_minor), 0) from charges where idempotency_key = ?")
        .param(idempotencyKey.toString())
        .query(Long.class)
        .single();
  }

  public void fault(String mode) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/sandbox/faults"))
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"" + mode + "\"}"))
            .build();
    int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (status != 200) {
      throw new IllegalStateException("setting sandbox fault answered " + status);
    }
  }

  private static SandboxProcess start() {
    PostgreSQLContainer postgres = new PostgreSQLContainer(Containers.POSTGRES_IMAGE);
    postgres.start();
    int port = freePort();
    Path jar = jar();
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(System.getProperty("java.home") + "/bin/java");
    try {
      Path log = Files.createTempFile("payment-sandbox", ".log");
      Process process =
          new ProcessBuilder(
                  List.of(
                      java,
                      "-jar",
                      jar.toString(),
                      "--server.port=" + port,
                      "--spring.datasource.url=" + postgres.getJdbcUrl(),
                      "--spring.datasource.username=" + postgres.getUsername(),
                      "--spring.datasource.password=" + postgres.getPassword(),
                      "--sandbox.api-key=" + API_KEY,
                      "--sandbox.stall=" + STALL.toMillis() + "ms"))
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
      String baseUrl = "http://127.0.0.1:" + port;
      awaitHealthy(baseUrl, process, log);
      DriverManagerDataSource data =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      return new SandboxProcess(baseUrl, JdbcClient.create(data));
    } catch (IOException e) {
      throw new IllegalStateException("could not start payment-sandbox", e);
    }
  }

  /** The sandbox module's runnable jar. Maven builds it first because this module depends on it. */
  private static Path jar() {
    File target = new File("../payment-sandbox/target");
    File[] jars =
        target.listFiles(
            f -> f.getName().startsWith("payment-sandbox-") && f.getName().endsWith(".jar"));
    if (jars == null || jars.length != 1) {
      throw new IllegalStateException(
          "expected one payment-sandbox jar in "
              + target.getAbsolutePath()
              + "; build it first (./mvnw package -pl services/payment-sandbox)");
    }
    return jars[0].toPath();
  }

  private static void awaitHealthy(String baseUrl, Process process, Path log) throws IOException {
    HttpClient client = HttpClient.newHttpClient();
    Instant deadline = Instant.now().plusSeconds(90);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new IllegalStateException("payment-sandbox exited:\n" + Files.readString(log));
      }
      try {
        HttpRequest health =
            HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health/readiness")).build();
        if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
          return;
        }
      } catch (IOException notYet) {
        // Not listening yet.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      sleep(250);
    }
    process.destroy();
    throw new IllegalStateException(
        "payment-sandbox never became ready:\n" + Files.readString(log));
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
