package dev.commerceflow.sandbox;

import dev.commerceflow.sandbox.SandboxApplication.SandboxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every API call needs "Authorization: Bearer &lt;sandbox api key&gt;", as with a real provider's
 * secret key. Compared in constant time. Health endpoints stay open for the container check.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

  private final byte[] expected;

  ApiKeyFilter(SandboxProperties properties) {
    if (properties.apiKey() == null || properties.apiKey().length() < 16) {
      throw new IllegalStateException("SANDBOX_API_KEY must be set (at least 16 characters)");
    }
    this.expected = ("Bearer " + properties.apiKey()).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator/health");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(given, expected)) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"invalid_api_key\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
