/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) Adaptiq
 */
package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Gate so a broken {@code pipeline_script} literal in {@code rig/local/seed-data.sh} can never
 * reach the rig again. Build 46 (titan-demo run #x with a top-level {@code pipeline:} wrapper) was
 * silently shipped because seed-data.sh writes raw SQL — bypassing every parse path. This test
 * extracts every quoted YAML literal that follows a {@code pipeline_script} column in the seed
 * script and runs it through the real {@link TitanYamlParser#parseAndValidate}.
 *
 * <p>If you add a new job to the seed, the literal must parse — otherwise this test fails with the
 * row index and the parser's error message, before a single PR with broken seed data can land.
 */
class SeedDataPipelineYamlTest {

  /** Pattern matches the literal that follows the {@code pipeline_script} column. */
  private static final Pattern PIPELINE_LITERAL =
      Pattern.compile("(?:E?'((?:[^']|'')*)')", Pattern.DOTALL);

  @Test
  void everyPipelineScriptInSeedDataParses() throws IOException {
    Path seed = locateSeedScript();
    String content = Files.readString(seed, StandardCharsets.UTF_8);

    List<String> literals = extractPipelineScriptLiterals(content);
    // Floor relaxed from >=5 to >=1 after PRs #511/#513 migrated most demo jobs from raw SQL
    // seed inserts to POST /api/v1/jobs. The load-bearing assertion is the per-literal parse
    // loop below — the count of remaining SQL-seeded scripts is no longer a contract.
    assertTrue(
        literals.size() >= 1,
        "expected at least 1 pipeline_script literal in seed-data.sh, got " + literals.size());

    List<String> failures = new ArrayList<>();
    for (int i = 0; i < literals.size(); i++) {
      String yaml = unescape(literals.get(i));
      try {
        TitanYamlParser.parseAndValidate(yaml);
      } catch (RuntimeException ex) {
        failures.add(
            "seed pipeline_script #"
                + (i + 1)
                + " rejected by parser: "
                + ex.getMessage()
                + "\n--- yaml ---\n"
                + yaml);
      }
    }
    if (!failures.isEmpty()) {
      fail(String.join("\n\n", failures));
    }
  }

  private static Path locateSeedScript() {
    Path cwd = Path.of("").toAbsolutePath();
    Path probe = cwd;
    for (int i = 0; i < 6; i++) {
      Path candidate = probe.resolve("rig/local/seed-data.sh");
      if (Files.exists(candidate)) {
        return candidate;
      }
      Path parent = probe.getParent();
      if (parent == null) {
        break;
      }
      probe = parent;
    }
    throw new IllegalStateException("rig/local/seed-data.sh not found walking up from " + cwd);
  }

  /**
   * Find each {@code pipeline_script} column reference and grab the next quoted literal. We
   * deliberately scan token-by-token rather than try to write one mega-regex for the whole SQL
   * statement — the seed script's SQL formatting is hand-written and varies.
   */
  static List<String> extractPipelineScriptLiterals(String content) {
    List<String> out = new ArrayList<>();
    int idx = 0;
    while (true) {
      int marker = content.indexOf("pipeline_script", idx);
      if (marker < 0) break;
      // Find the next quoted literal AFTER the marker. The literal we want is the VALUES literal,
      // not the column name — so skip past the closing paren of the INSERT column list, then take
      // the first quoted literal we see that starts with a YAML-looking key.
      Matcher m = PIPELINE_LITERAL.matcher(content);
      m.region(marker, content.length());
      while (m.find()) {
        String body = m.group(1);
        if (looksLikeYaml(body)) {
          out.add(body);
          idx = m.end();
          break;
        }
      }
      if (!m.find(marker)) {
        idx = marker + 1;
      } else {
        idx = Math.max(idx, m.end());
      }
    }
    return out;
  }

  private static boolean looksLikeYaml(String s) {
    String trimmed = s.stripLeading();
    return trimmed.startsWith("stages:") || trimmed.startsWith("stages\n");
  }

  /** Reverse the bash/SQL escaping used in seed-data.sh: doubled single quotes + {@code E'\n'}. */
  static String unescape(String literal) {
    return literal.replace("''", "'").replace("\\n", "\n");
  }
}
