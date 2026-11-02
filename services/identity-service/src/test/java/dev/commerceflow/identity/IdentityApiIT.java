package dev.commerceflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.identity.token.TokenService;
import dev.commerceflow.security.Jwts;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class IdentityApiIT {

  static final String ADMIN_EMAIL = "root-admin@commerceflow.local";
  static final String PASSWORD = "correct horse battery staple";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.identity.jwt-private-key-path", TestKeys::privateKeyPath);
    registry.add("commerceflow.identity.bootstrap-admin-email", () -> ADMIN_EMAIL);
    registry.add(
        "commerceflow.identity.bootstrap-admin-password", () -> "admin password for tests");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired JwtEncoder serviceEncoder;
  @Autowired ApplicationContext context;
  final JsonMapper json = JsonMapper.builder().build();

  // --- Registration --------------------------------------------------------------------------

  @Test
  void registersACustomerAndStoresOnlyAnArgon2idHash() throws Exception {
    String email = uniqueEmail();
    mvc.perform(register(email, PASSWORD))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());

    String hash =
        jdbc.sql("select password_hash from users where email = ?")
            .param(email)
            .query(String.class)
            .single();
    assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
  }

  @Test
  void emailsAreCaseInsensitivelyUnique() throws Exception {
    String email = uniqueEmail();
    mvc.perform(register(email, PASSWORD)).andExpect(status().isCreated());
    mvc.perform(register(email.toUpperCase(), PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/email-taken"));
  }

  @Test
  void rejectsInvalidInputWithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"not-an-email\",\"password\":\"short\",\"displayName\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
        .andExpect(
            jsonPath("$.errors[*].field")
                .value(org.hamcrest.Matchers.hasItems("email", "password", "displayName")))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void rejectsAPasswordContainingTheEmailName() throws Exception {
    String email = "margaretx@example.com";
    mvc.perform(register(email, "my margaretx password"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/weak-password"));
  }

  // --- Login ---------------------------------------------------------------------------------

  @Test
  void loginIssuesAnAccessTokenAndAnHttpOnlyRefreshCookie() throws Exception {
    String email = registered();
    MvcResult result =
        mvc.perform(login(email, PASSWORD))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();

    String cookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(cookie)
        .startsWith("cf_refresh=")
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");

    Map<String, Object> claims = claimsOf(body(result).get("accessToken").asString());
    assertThat(claims)
        .containsEntry("iss", Jwts.ISSUER)
        .containsEntry("roles", List.of("CUSTOMER"))
        .containsKey("sid")
        .containsKey("jti")
        .doesNotContainKey("email");
    // RFC 7519 allows a single audience as a string; Nimbus writes it that way.
    assertThat(claims.get("aud")).isIn(Jwts.AUDIENCE, List.of(Jwts.AUDIENCE));
  }

  @Test
  void unknownEmailAndWrongPasswordGetIdenticalResponses() throws Exception {
    String email = registered();
    String wrongPassword =
        mvc.perform(login(email, "wrong password entirely"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknownEmail =
        mvc.perform(login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(withoutCorrelationId(wrongPassword)).isEqualTo(withoutCorrelationId(unknownEmail));
  }

  @Test
  void locksTheAccountAfterFiveFailuresEvenForTheRightPassword() throws Exception {
    String email = registered();
    for (int i = 0; i < 5; i++) {
      mvc.perform(login(email, "wrong password number " + i)).andExpect(status().isUnauthorized());
    }
    mvc.perform(login(email, PASSWORD)).andExpect(status().isUnauthorized());

    Instant lockedUntil =
        jdbc.sql("select locked_until from users where email = ?")
            .param(email)
            .query(Instant.class)
            .single();
    assertThat(lockedUntil).isAfter(Instant.now());
    assertThat(auditCount("ACCOUNT_LOCKED", email)).isEqualTo(1);
  }

  @Test
  void auditRowsNeverContainPasswordsOrUnknownEmails() throws Exception {
    String secret = "Sup3r-Secret-" + UUID.randomUUID();
    String unknown = "ghost-" + UUID.randomUUID() + "@example.com";
    mvc.perform(login(unknown, secret)).andExpect(status().isUnauthorized());

    long leaks =
        jdbc.sql(
                "select count(*) from audit_events where details::text like ? or details::text like ?")
            .param("%" + secret + "%")
            .param("%" + unknown + "%")
            .query(Long.class)
            .single();
    assertThat(leaks).isZero();
  }

  // --- Refresh and logout --------------------------------------------------------------------

  @Test
  void refreshRotatesAndReuseRevokesTheWholeSession() throws Exception {
    String first =
        body(mvc.perform(login(registered(), PASSWORD).header("X-Token-Delivery", "body"))
                .andReturn())
            .get("refreshToken")
            .asString();

    String second =
        body(mvc.perform(refresh(first)).andExpect(status().isOk()).andReturn())
            .get("refreshToken")
            .asString();
    assertThat(second).isNotEqualTo(first);

    mvc.perform(refresh(first))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("/problems/invalid-refresh-token"));
    // The thief replayed the old token; the legitimate holder's newer token must die too.
    mvc.perform(refresh(second)).andExpect(status().isUnauthorized());
  }

  @Test
  void concurrentRefreshesWithOneTokenLetAtMostOneThrough() throws Exception {
    String token =
        body(mvc.perform(login(registered(), PASSWORD).header("X-Token-Delivery", "body"))
                .andReturn())
            .get("refreshToken")
            .asString();

    Callable<Integer> attempt =
        () -> mvc.perform(refresh(token)).andReturn().getResponse().getStatus();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results = pool.invokeAll(List.of(attempt, attempt));
      List<Integer> statuses =
          results.stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .toList();
      // The row lock serialises them: one rotates, the other then looks like reuse.
      assertThat(statuses).containsExactlyInAnyOrder(200, 401);
    }
  }

  @Test
  void refreshWorksFromTheCookie() throws Exception {
    MvcResult loggedIn = mvc.perform(login(registered(), PASSWORD)).andReturn();
    String cookieValue = loggedIn.getResponse().getCookie("cf_refresh").getValue();

    mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("cf_refresh", cookieValue)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @Test
  void refreshTokensAreStoredOnlyAsHashes() throws Exception {
    String raw =
        body(mvc.perform(login(registered(), PASSWORD).header("X-Token-Delivery", "body"))
                .andReturn())
            .get("refreshToken")
            .asString();

    long byHash =
        jdbc.sql("select count(*) from refresh_tokens where token_hash = ?")
            .param(TokenService.hash(raw))
            .query(Long.class)
            .single();
    long byRaw =
        jdbc.sql("select count(*) from refresh_tokens where token_hash = ?")
            .param(raw.getBytes())
            .query(Long.class)
            .single();
    assertThat(byHash).isEqualTo(1);
    assertThat(byRaw).isZero();
  }

  @Test
  void logoutEndsTheSession() throws Exception {
    JsonNode tokens =
        body(
            mvc.perform(login(registered(), PASSWORD).header("X-Token-Delivery", "body"))
                .andReturn());

    mvc.perform(
            post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asString()))
        .andExpect(status().isNoContent())
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    mvc.perform(refresh(tokens.get("refreshToken").asString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void garbageRefreshTokenIsRejected() throws Exception {
    mvc.perform(refresh("not-a-real-token")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
  }

  // --- Access tokens -------------------------------------------------------------------------

  @Test
  void meReturnsTheCallerFromTheToken() throws Exception {
    String email = registered();
    String access =
        body(mvc.perform(login(email, PASSWORD)).andReturn()).get("accessToken").asString();
    mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
  }

  @Test
  void missingTokenIsA401ProblemWithCorrelationId() throws Exception {
    mvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void rejectsForgedExpiredWrongAudienceAndUnsignedTokens() throws Exception {
    String subject = UUID.randomUUID().toString();
    Instant now = Instant.now();

    String forged =
        sign(TestKeys.attackerEncoder(), claims(subject, now.plusSeconds(600), Jwts.AUDIENCE));
    String expired = sign(serviceEncoder, claims(subject, now.minusSeconds(3600), Jwts.AUDIENCE));
    String wrongAudience =
        sign(serviceEncoder, claims(subject, now.plusSeconds(600), "some-other-app"));
    String unsigned = unsignedToken(subject, now.plusSeconds(600));

    for (String token : List.of(forged, expired, wrongAudience, unsigned)) {
      mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void jwksPublishesOnlyPublicKeyMaterial() throws Exception {
    JsonNode key =
        body(mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andReturn())
            .get("keys")
            .get(0);
    assertThat(key.get("kty").asString()).isEqualTo("RSA");
    assertThat(key.has("kid")).isTrue();
    assertThat(key.has("n")).isTrue();
    for (String privatePart : List.of("d", "p", "q", "dp", "dq", "qi")) {
      assertThat(key.has(privatePart)).as("JWKS must not contain %s", privatePart).isFalse();
    }
  }

  // --- Admin bootstrap and correlation ids ---------------------------------------------------

  @Test
  void bootstrapCreatesExactlyOneAdminAndIsIdempotent() throws Exception {
    for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
      runner.run(null);
    }
    List<String> admins =
        jdbc.sql("select email from users where role = 'ADMIN'").query(String.class).list();
    assertThat(admins).containsExactly(ADMIN_EMAIL);
  }

  @Test
  void echoesSafeCorrelationIdsAndReplacesUnsafeOnes() throws Exception {
    mvc.perform(get("/.well-known/jwks.json").header("X-Correlation-Id", "req-123.abc"))
        .andExpect(header().string("X-Correlation-Id", "req-123.abc"));
    String replaced =
        mvc.perform(
                get("/.well-known/jwks.json").header("X-Correlation-Id", "evil\nInjected: header"))
            .andReturn()
            .getResponse()
            .getHeader("X-Correlation-Id");
    assertThat(replaced).matches("[0-9a-f-]{36}");
  }

  // --- Helpers -------------------------------------------------------------------------------

  static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  String registered() throws Exception {
    String email = uniqueEmail();
    mvc.perform(register(email, PASSWORD)).andExpect(status().isCreated());
    return email;
  }

  static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
      String email, String password) {
    return post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test User\"}"
                .formatted(email, password));
  }

  static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
      String email, String password) {
    return post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
  }

  static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refresh(
      String token) {
    return post("/api/v1/auth/refresh")
        .header("X-Token-Delivery", "body")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"refreshToken\":\"%s\"}".formatted(token));
  }

  JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  Map<String, Object> claimsOf(String jwt) {
    byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
    return json.readValue(payload, Map.class);
  }

  long auditCount(String action, String email) {
    return jdbc.sql(
            "select count(*) from audit_events a join users u on u.id = a.actor_id"
                + " where a.action = ? and u.email = ?")
        .param(action)
        .param(email)
        .query(Long.class)
        .single();
  }

  String withoutCorrelationId(String problemJson) {
    Map<?, ?> map = json.readValue(problemJson, Map.class);
    map.remove("correlationId");
    return map.toString();
  }

  static JwtClaimsSet claims(String subject, Instant expiresAt, String audience) {
    return JwtClaimsSet.builder()
        .issuer(Jwts.ISSUER)
        .audience(List.of(audience))
        .subject(subject)
        .issuedAt(expiresAt.minusSeconds(900))
        .expiresAt(expiresAt)
        .claim("roles", List.of("ADMIN"))
        .build();
  }

  static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
        .getTokenValue();
  }

  /** The classic "alg: none" attack: a token with no signature at all. */
  static String unsignedToken(String subject, Instant expiresAt) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
    String payload =
        b64.encodeToString(
            ("{\"iss\":\"%s\",\"aud\":[\"%s\"],\"sub\":\"%s\",\"exp\":%d,\"roles\":[\"ADMIN\"]}"
                    .formatted(Jwts.ISSUER, Jwts.AUDIENCE, subject, expiresAt.getEpochSecond()))
                .getBytes());
    return header + "." + payload + ".";
  }
}
