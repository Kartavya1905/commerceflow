package dev.commerceflow.web.security;

import dev.commerceflow.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 written as RFC 9457 problems, in the same shape as every other error. The message
 * never says why a token was rejected (expired, wrong key, malformed), which would help an attacker
 * probe the validator.
 */
public final class ProblemSecurityHandlers {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ProblemSecurityHandlers() {}

  public static AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, e) -> {
      response.setHeader("WWW-Authenticate", "Bearer");
      write(response, 401, "unauthorized", "Unauthorized", "A valid access token is required.");
    };
  }

  public static AccessDeniedHandler accessDeniedHandler() {
    return (request, response, e) ->
        write(response, 403, "forbidden", "Forbidden", "You are not allowed to do this.");
  }

  private static void write(
      HttpServletResponse response, int status, String type, String title, String detail)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "/problems/" + type);
    body.put("title", title);
    body.put("status", status);
    body.put("detail", detail);
    body.put("correlationId", CorrelationIdFilter.current());
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    JSON.writeValue(response.getOutputStream(), body);
  }
}
