package dev.commerceflow.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Lists and reads contract files from the test classpath. */
final class ContractResources {

  static final String ENVELOPE = "envelope";

  private ContractResources() {}

  static List<Path> schemaFiles() {
    return files(EventContracts.SCHEMA_CLASSPATH_DIR, ".schema.json");
  }

  static List<Path> eventSchemaFiles() {
    return schemaFiles().stream().filter(p -> !typeOf(p).equals(ENVELOPE)).toList();
  }

  static List<Path> validExamples() {
    return files("examples/valid/", ".json");
  }

  static List<Path> invalidExamples() {
    return files("examples/invalid/", ".json").stream()
        .filter(p -> !p.getFileName().toString().equals("expectations.json"))
        .toList();
  }

  static List<Path> openApiFiles() {
    return files("openapi/", ".yaml");
  }

  static Path resource(String classpathPath) {
    URL url = ContractResources.class.getClassLoader().getResource(classpathPath);
    if (url == null) {
      throw new IllegalStateException("Missing test resource " + classpathPath);
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Event type a contract file is about: {@code OrderCreated.schema.json}, {@code
   * OrderCreated.json} and {@code OrderCreated--negative-quantity.json} all give {@code
   * OrderCreated}.
   */
  static String typeOf(Path file) {
    String name = file.getFileName().toString();
    int end = name.indexOf("--");
    if (end < 0) {
      end = name.indexOf('.');
    }
    return name.substring(0, end);
  }

  private static List<Path> files(String classpathDir, String suffix) {
    try (Stream<Path> listing = Files.list(resource(classpathDir))) {
      return listing.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
