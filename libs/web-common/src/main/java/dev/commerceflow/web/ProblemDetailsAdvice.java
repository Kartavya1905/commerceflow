package dev.commerceflow.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an RFC 9457 problem with a correlation id. Unexpected exceptions
 * become a bare 500: the stack trace goes to the log, never to the client.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> handleApi(ApiException e, WebRequest request) {
    ProblemDetail problem = problem(e.status(), e.type(), e.title(), e.getMessage());
    return handleExceptionInternal(e, problem, new HttpHeaders(), e.status(), request);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<Object> handleStaleVersion(OptimisticLockingFailureException e, WebRequest req) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "stale-version",
            "Conflict",
            "The resource was changed by someone else. Reload it and try again.");
    return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.CONFLICT, req);
  }

  /**
   * Spring Security decides 401 and 403. Re-throwing hands these back to its filter chain instead
   * of letting the catch-all below turn an authorisation failure into a 500.
   */
  @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
  void rethrowSecurity(RuntimeException e) {
    throw e;
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
    log.error("Unhandled exception", e);
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal error",
            "Something went wrong. Quote the correlation id if you report it.");
    return handleExceptionInternal(
        e, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    Map.of(
                        "field",
                        f.getField(),
                        "message",
                        f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
            .toList();
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Validation failed",
            "One or more fields are invalid.");
    problem.setProperty("errors", errors);
    return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  /** Adds the correlation id to every problem this handler (or its superclass) produces. */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      problem.setProperty("correlationId", CorrelationIdFilter.current());
    }
    return super.createResponseEntity(body, headers, status, request);
  }

  static ProblemDetail problem(HttpStatusCode status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("/problems/" + type));
    problem.setTitle(title);
    return problem;
  }
}
