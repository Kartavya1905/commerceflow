package dev.commerceflow.security;

import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Runs {@link LogRedaction} over every string value of every structured (JSON) log line: the
 * message, the exception message and the stack trace alike. Every service turns it on with {@code
 * logging.structured.json.customizer: dev.commerceflow.security.RedactingLogCustomizer}.
 */
public class RedactingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

  @Override
  public void customize(Members<Object> members) {
    members.applyingValueProcessor(ValueProcessor.of(String.class, LogRedaction::redact));
  }
}
