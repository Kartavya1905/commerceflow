package dev.commerceflow.messaging;

import java.util.Map;

/**
 * Carries the trace across the outbox (ARCHITECTURE.md J, NFR-08). The publisher sends rows later,
 * on its own thread, where the request's trace is long gone; so the trace context of the request
 * that wrote the event is stored with the row ({@code traceparent}, W3C) and sent as a Kafka
 * header. The consumer's listener observation continues the trace from it, and one trace spans the
 * whole checkout instead of breaking at every outbox. {@link MicrometerTraceHeaders} is the
 * implementation when tracing is on the classpath.
 */
public interface TraceHeaders {

  /** Adds the current trace context to the headers, if there is one. */
  void inject(Map<String, String> headers);

  TraceHeaders NONE = headers -> {};
}
