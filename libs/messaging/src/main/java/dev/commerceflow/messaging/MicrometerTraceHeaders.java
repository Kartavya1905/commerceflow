package dev.commerceflow.messaging;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;

/** {@link TraceHeaders} with Micrometer Tracing (W3C {@code traceparent}). */
final class MicrometerTraceHeaders implements TraceHeaders {

  private final Tracer tracer;
  private final Propagator propagator;

  MicrometerTraceHeaders(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public void inject(Map<String, String> headers) {
    TraceContext context = tracer.currentTraceContext().context();
    if (context != null) {
      propagator.inject(context, headers, Map::put);
    }
  }
}
