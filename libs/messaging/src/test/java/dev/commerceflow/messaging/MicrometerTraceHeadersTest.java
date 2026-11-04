package dev.commerceflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The outbox stores the trace of the request that wrote the event, and nothing without one. */
class MicrometerTraceHeadersTest {

  final Tracer tracer = mock(Tracer.class);
  final CurrentTraceContext current = mock(CurrentTraceContext.class);
  final Propagator propagator = mock(Propagator.class);

  @Test
  @SuppressWarnings("unchecked")
  void writesTheCurrentTraceIntoTheHeaders() {
    TraceContext context = mock(TraceContext.class);
    when(tracer.currentTraceContext()).thenReturn(current);
    when(current.context()).thenReturn(context);
    doAnswer(
            call -> {
              Propagator.Setter<Map<String, String>> setter = call.getArgument(2);
              setter.set(
                  call.getArgument(1),
                  "traceparent",
                  "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
              return null;
            })
        .when(propagator)
        .inject(eq(context), any(), any(Propagator.Setter.class));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Correlation-Id", "c-1");
    new MicrometerTraceHeaders(tracer, propagator).inject(headers);

    assertThat(headers)
        .containsEntry("X-Correlation-Id", "c-1")
        .containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
  }

  @Test
  void withNoTraceInProgressItAddsNothing() {
    when(tracer.currentTraceContext()).thenReturn(current);
    when(current.context()).thenReturn(null);
    Map<String, String> headers = new LinkedHashMap<>();

    new MicrometerTraceHeaders(tracer, propagator).inject(headers);

    assertThat(headers).isEmpty();
    verifyNoInteractions(propagator);
  }

  @Test
  void withoutTracingTheHeadersAreLeftAlone() {
    Map<String, String> headers = new LinkedHashMap<>(Map.of("X-Correlation-Id", "c-1"));
    TraceHeaders.NONE.inject(headers);
    assertThat(headers).containsOnlyKeys("X-Correlation-Id");
  }
}
