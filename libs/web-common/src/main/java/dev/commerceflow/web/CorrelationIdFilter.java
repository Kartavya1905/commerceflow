package dev.commerceflow.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id, puts it in the logging context, and echoes it in the
 * response.
 *
 * <p>An incoming {@code X-Correlation-Id} is reused only if it is short and made of safe
 * characters; anything else is replaced, so a client cannot inject arbitrary text into log lines.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = sanitize(request.getHeader(HEADER));
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  static String sanitize(String candidate) {
    return candidate != null && SAFE.matcher(candidate).matches()
        ? candidate
        : UUID.randomUUID().toString();
  }

  /** The current request's correlation id, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
