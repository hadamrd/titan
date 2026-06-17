package io.adaptiq.titan.worker.step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@link StepHandler} needs to do its work — the immutable input to {@link
 * StepHandler#execute} (Chunk 32A — design/32 §3.1). Orca's {@code StageExecution} context in Titan
 * terms.
 *
 * <p>It carries pure data (the resolved arguments, the workspace, the environment, the build / node
 * ids) plus the collaborators the handler uses but does not own: the {@link StepExecutor} to run
 * processes in, the {@link LogSink} to write logs to, the {@link OutputSink} to publish outputs to,
 * the {@link ArtifactSink} to archive build artifacts through, and the {@link TestResultSink} to
 * publish parsed test cases through (issue #298). A handler never reaches past these into the
 * worker or the engine.
 *
 * <p>All {@code ${{ … }}} references in {@link #arguments()} are already resolved — the
 * orchestrator substituted them at dispatch (design/29 §6).
 *
 * @param descriptorId the step type — selects the handler
 * @param arguments the step's resolved arguments, as written in the pipeline
 * @param workDir the step's working directory
 * @param env the step environment (payload-derived only — design/26 Tier C)
 * @param buildId the owning build
 * @param nodeId the owning {@code flow_nodes} node
 * @param image the container image to run process commands in, or {@code null} for local
 * @param executor the environment the handler runs commands in (local / container)
 * @param log the handler's log sink
 * @param outputs the handler's output sink ({@code setOutput})
 * @param artifacts the handler's artifact sink ({@code archiveArtifacts} — design/41 §8.5)
 * @param testReports the handler's parsed-test-result sink ({@code junit} — issue #298)
 */
public record StepRequest(
    String descriptorId,
    Map<String, Object> arguments,
    Path workDir,
    Map<String, String> env,
    long buildId,
    String nodeId,
    String image,
    StepExecutor executor,
    LogSink log,
    OutputSink outputs,
    ArtifactSink artifacts,
    TestResultSink testReports) {

  public StepRequest {
    // Tolerant of null values (a JSON argument may be null) — so not Map.copyOf.
    arguments =
        arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    // A request always has an artifact sink — never null. A call site with no store wired
    // gets the loud-failing UNCONFIGURED sink rather than a NullPointerException at use.
    if (artifacts == null) {
      artifacts = ArtifactSink.UNCONFIGURED;
    }
    // A request always has a test-result sink — never null. The default is the silent
    // UNCONFIGURED (issue #298): per-case persistence is additive, never required.
    if (testReports == null) {
      testReports = TestResultSink.UNCONFIGURED;
    }
  }

  /**
   * Convenience constructor for call sites that have no artifact store — tests, and any step path
   * predating the artifact SPI. {@code artifacts} defaults to {@link ArtifactSink#UNCONFIGURED}: a
   * step that does not archive is unaffected; one that tries gets a clear, recorded failure.
   */
  public StepRequest(
      String descriptorId,
      Map<String, Object> arguments,
      Path workDir,
      Map<String, String> env,
      long buildId,
      String nodeId,
      String image,
      StepExecutor executor,
      LogSink log,
      OutputSink outputs) {
    this(
        descriptorId,
        arguments,
        workDir,
        env,
        buildId,
        nodeId,
        image,
        executor,
        log,
        outputs,
        ArtifactSink.UNCONFIGURED,
        TestResultSink.UNCONFIGURED);
  }

  /**
   * Convenience constructor for the historical 11-arg shape (with an {@link ArtifactSink} but no
   * {@link TestResultSink}). Issue #298 introduced {@code testReports}; the pre-#298 sites at the
   * worker and in the TCK keep compiling unchanged by going through this overload, which fills in
   * {@link TestResultSink#UNCONFIGURED}.
   */
  public StepRequest(
      String descriptorId,
      Map<String, Object> arguments,
      Path workDir,
      Map<String, String> env,
      long buildId,
      String nodeId,
      String image,
      StepExecutor executor,
      LogSink log,
      OutputSink outputs,
      ArtifactSink artifacts) {
    this(
        descriptorId,
        arguments,
        workDir,
        env,
        buildId,
        nodeId,
        image,
        executor,
        log,
        outputs,
        artifacts,
        TestResultSink.UNCONFIGURED);
  }

  /** A string-valued argument, or {@code null} if absent. */
  public String argString(String key) {
    Object v = arguments.get(key);
    return v == null ? null : String.valueOf(v);
  }

  /** A string-valued argument, or {@code fallback} if absent. */
  public String argString(String key, String fallback) {
    String v = argString(key);
    return v == null ? fallback : v;
  }

  /** A boolean-valued argument ({@code Boolean} or {@code "true"}/{@code "false"} string). */
  public boolean argBoolean(String key, boolean fallback) {
    Object v = arguments.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof String s) {
      return Boolean.parseBoolean(s);
    }
    return fallback;
  }

  /** A list-of-strings argument, or an empty list if absent / not a list. */
  public List<String> argStringList(String key) {
    Object v = arguments.get(key);
    if (!(v instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object item : list) {
      out.add(item == null ? null : String.valueOf(item));
    }
    return out;
  }
}
