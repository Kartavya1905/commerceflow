package dev.commerceflow.web;

import org.springframework.http.HttpStatus;

/**
 * A failure the client should see as an RFC 9457 problem. The {@code type} slug becomes {@code
 * /problems/<type>}; {@code detail} must be safe to show to the caller.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String type;
  private final String title;

  public ApiException(HttpStatus status, String type, String title, String detail) {
    super(detail);
    this.status = status;
    this.type = type;
    this.title = title;
  }

  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", what + " not found");
  }

  public static ApiException conflict(String type, String detail) {
    return new ApiException(HttpStatus.CONFLICT, type, "Conflict", detail);
  }

  public HttpStatus status() {
    return status;
  }

  public String type() {
    return type;
  }

  public String title() {
    return title;
  }
}
