package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Builds {@link PulsarClient}s for a configured Pulsar node endpoint (issue #1280). Mirrors {@link
 * io.adaptiq.titan.scm.github.GithubClientFactory} — extracted as a seam so the scanner/scheduler
 * stay node-agnostic and tests can point the client at a WireMock endpoint via {@link
 * #forNode(String)}.
 *
 * <p>The default endpoint is the in-cluster Pulsar service; rigs and tests override it.
 */
public final class PulsarClientFactory {

  /** Default in-cluster Pulsar node endpoint. Override per rig / in tests. */
  public static final String DEFAULT_ENDPOINT = "http://pulsar:8080";

  private final String defaultEndpoint;

  public PulsarClientFactory() {
    this(DEFAULT_ENDPOINT);
  }

  public PulsarClientFactory(@NonNull String defaultEndpoint) {
    this.defaultEndpoint = Objects.requireNonNull(defaultEndpoint, "defaultEndpoint");
  }

  /** Client for the factory's default-configured node. */
  @NonNull
  public PulsarClient client() {
    return new PulsarClient(defaultEndpoint);
  }

  /** Client for an explicit node endpoint (multi-node deployments, tests). */
  @NonNull
  public PulsarClient forNode(@NonNull String endpoint) {
    return new PulsarClient(endpoint);
  }
}
