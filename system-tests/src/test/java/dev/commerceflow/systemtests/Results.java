package dev.commerceflow.systemtests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * One JSON line per scenario and run, in system-tests/target/failure-recovery/results.jsonl. The
 * report (docs/reports/failure-recovery.md) is written from these numbers, not from memory.
 */
final class Results {

  private final Path file;
  private final String run;
  private final JsonMapper json = JsonMapper.builder().build();

  Results(Stack stack) {
    this.file = stack.root.resolve("system-tests/target/failure-recovery/results.jsonl");
    String configured = System.getProperty("commerceflow.run");
    this.run = configured == null || configured.startsWith("${") ? "adhoc" : configured;
  }

  /** Starts a record for one scenario; fill it in, then {@link #write}. */
  Map<String, Object> start(String scenario) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("scenario", scenario);
    record.put("run", run);
    record.put("startedAt", Instant.now().toString());
    return record;
  }

  void write(Map<String, Object> record) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          json.writeValueAsString(record) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
