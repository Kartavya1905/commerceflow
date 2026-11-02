package dev.commerceflow.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.commerceflow.security.Jwts;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Plays every downstream service: serves a JWKS (as identity-service would) and records each
 * request it receives, so tests can prove a blocked request never reached a service.
 */
final class FakeBackend {

  record Seen(String method, String path, String correlationId, String authorization) {}

  private final HttpServer server;
  private final RSAKey signingKey;
  private final JwtEncoder encoder;
  private final JwtEncoder attackerEncoder;
  final Queue<Seen> requests = new ConcurrentLinkedQueue<>();

  FakeBackend() throws Exception {
    signingKey = rsaKey("test-key");
    encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    attackerEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey("test-key"))));

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/.well-known/jwks.json",
        exchange -> respond(exchange, new JWKSet(signingKey.toPublicJWK()).toString()));
    server.createContext("/", this::record);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void stop() {
    server.stop(0);
  }

  boolean sawPath(String path) {
    return requests.stream().anyMatch(r -> r.path().equals(path));
  }

  String token(String role) {
    return sign(encoder, role, Instant.now().plusSeconds(600));
  }

  String expiredToken() {
    return sign(encoder, "ADMIN", Instant.now().minusSeconds(3600));
  }

  /** Same kid as the real key, different key material: must fail signature checks. */
  String forgedToken() {
    return sign(attackerEncoder, "ADMIN", Instant.now().plusSeconds(600));
  }

  private void record(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    requests.add(
        new Seen(
            exchange.getRequestMethod(),
            path,
            exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
            exchange.getRequestHeaders().getFirst("Authorization")));
    exchange.getRequestBody().readAllBytes();
    // Services send their own security headers too; the gateway must not duplicate them.
    exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
    respond(exchange, "{\"servedPath\":\"" + path + "\"}");
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static String sign(JwtEncoder encoder, String role, Instant expiresAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(Jwts.ISSUER)
            .audience(List.of(Jwts.AUDIENCE))
            .subject(UUID.randomUUID().toString())
            .issuedAt(expiresAt.minusSeconds(900))
            .expiresAt(expiresAt)
            .claim(Jwts.ROLES_CLAIM, List.of(role))
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private static RSAKey rsaKey(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
        .privateKey((RSAPrivateKey) pair.getPrivate())
        .keyID(kid)
        .build();
  }
}
