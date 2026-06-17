package io.adaptiq.titan.chaos;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads chaos pipeline YAML fixtures from {@code chaos/fixtures/} on the classpath. */
final class ChaosFixtures {
  private static final String BASE = "/io/adaptiq/titan/chaos/fixtures/";

  private ChaosFixtures() {}

  static String load(String fileName) {
    try (InputStream in = ChaosFixtures.class.getResourceAsStream(BASE + fileName)) {
      if (in == null) {
        throw new IllegalArgumentException("chaos fixture not found: " + BASE + fileName);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read chaos fixture " + fileName, e);
    }
  }
}
