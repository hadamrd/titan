package io.adaptiq.titan.worker.step.manifest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.exec.DockerClients;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/**
 * 42-T item 10 — wires the Tier-1 {@link ContainerManifestStepHandler} into the shared {@link
 * StepHandlerTck} so the manifest-backed handler passes the exact same conformance suite a socle or
 * Tier-2 handler must (design/42 §4.1, §4.8 — "the socle dogfoods the SPI").
 *
 * <p>The TCK's behavioural tests ({@code executeNeverReturnsNull}, {@code
 * executeOnValidArgumentsSucceeds}) run a real container through {@code ContainerExecutor} — {@code
 * ContainerManifestStepHandler} always runs in the manifest's own image (design/42 §4.8), so a
 * Docker / Podman daemon is required for those. The whole class self-skips when no daemon is
 * reachable; the pure-metadata TCK contract checks (descriptor stability/agreement) still run
 * everywhere because the skip is in {@code @BeforeEach} only when actually needed — here it is
 * applied uniformly since the TCK runs {@code execute} in two of its five checks. Where a daemon is
 * present (the rig's worker host) the full TCK runs against a {@code busybox} container step.
 */
class ContainerManifestStepHandlerTckTest extends StepHandlerTck {

  private static final StepManifest MANIFEST =
      new StepManifest(
          "tckEcho",
          "busybox:stable",
          List.of("echo", "titan-tck-${{ args.message }}"),
          List.of(new StepManifest.ManifestParam("message", "string", true)),
          "TCK Echo",
          "A container step used by the TCK — echoes its message argument.",
          "<tck-fixture>");

  @BeforeEach
  void requireDockerDaemon() {
    // ContainerManifestStepHandler runs its manifest image; the TCK's execute() checks need a
    // daemon. No daemon ⇒ skip the class with a clear reason (design/42 §4.8 container model).
    assumeTrue(dockerAvailable(), "no Docker/Podman daemon reachable — skipping container TCK");
  }

  private static boolean dockerAvailable() {
    try {
      DockerClient client = DockerClients.shared();
      client.pingCmd().exec();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  protected StepHandler newHandler() {
    return new ContainerManifestStepHandler(MANIFEST);
  }

  @Override
  protected StepExecutor newExecutor() {
    // ContainerManifestStepHandler builds its own ContainerExecutor for the manifest image;
    // this executor is unused by the handler, but the TCK requires a non-null one.
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("message", "green");
  }
}
