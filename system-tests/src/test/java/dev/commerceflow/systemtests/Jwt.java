package dev.commerceflow.systemtests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Takes a real access token apart and puts altered ones back together, to show that every way of
 * forging one is refused. Re-signing with the stack's own key (from the git-ignored secrets/
 * folder) proves the claim checks themselves (expiry, audience), not just the signature.
 */
final class Jwt {

  private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
  private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Jwt() {}

  static ObjectNode header(String token) {
    return (ObjectNode) JSON.readTree(DECODE.decode(token.split("\\.")[0]));
  }

  static ObjectNode claims(String token) {
    return (ObjectNode) JSON.readTree(DECODE.decode(token.split("\\.")[1]));
  }

  /** The same header and signature over changed claims: what an attacker can do without a key. */
  static String withClaimsUnsigned(String token, Consumer<ObjectNode> change) {
    String[] parts = token.split("\\.");
    ObjectNode claims = claims(token);
    change.accept(claims);
    return parts[0] + "." + encode(claims) + "." + parts[2];
  }

  /** {@code alg: none}, no signature at all. */
  static String unsigned(String token, Consumer<ObjectNode> change) {
    ObjectNode header = header(token);
    header.put("alg", "none");
    ObjectNode claims = claims(token);
    change.accept(claims);
    return encode(header) + "." + encode(claims) + ".";
  }

  /** Signed with a key the system has never seen, under the real key id. */
  static String signedByStranger(String token, Consumer<ObjectNode> change) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return sign(header(token), changed(token, change), generator.generateKeyPair().getPrivate());
  }

  /** Signed with the stack's own key: only the claims are wrong. */
  static String signedByIssuer(Path root, String token, Consumer<ObjectNode> change)
      throws Exception {
    String pem = Files.readString(root.resolve("secrets/jwt-private.pem"));
    String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    PrivateKey key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    return sign(header(token), changed(token, change), key);
  }

  private static ObjectNode changed(String token, Consumer<ObjectNode> change) {
    ObjectNode claims = claims(token);
    change.accept(claims);
    return claims;
  }

  private static String sign(ObjectNode header, ObjectNode claims, PrivateKey key)
      throws Exception {
    String signingInput = encode(header) + "." + encode(claims);
    Signature rs256 = Signature.getInstance("SHA256withRSA");
    rs256.initSign(key);
    rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + ENCODE.encodeToString(rs256.sign());
  }

  private static String encode(ObjectNode node) {
    return ENCODE.encodeToString(JSON.writeValueAsBytes(node));
  }
}
