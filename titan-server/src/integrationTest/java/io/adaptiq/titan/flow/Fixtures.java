package io.adaptiq.titan.flow;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads Titan pipeline test fixtures from {@code src/test/resources/.../flow/fixtures/}.
 *
 * <p>Pipeline definitions used by tests live as real {@code .yml} files, not inline Java strings —
 * they are diffable, syntax-highlighted, and reusable across the parser, validator and (later) bake
 * ITs.
 */
public final class Fixtures {

  private static final String BASE = "/io/adaptiq/titan/flow/fixtures/";

  private Fixtures() {}

  /** Read a fixture file (e.g. {@code "reference-pipeline.yml"}) as UTF-8 text. */
  public static String load(String fileName) {
    try (InputStream in = Fixtures.class.getResourceAsStream(BASE + fileName)) {
      if (in == null) {
        throw new IllegalArgumentException("fixture not found: " + BASE + fileName);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read fixture " + fileName, e);
    }
  }
}
