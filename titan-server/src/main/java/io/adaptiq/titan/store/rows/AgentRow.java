package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/** Mutable POJO mapping to a row in {@code titan.agents}. Natural key: agent_id. */
public class AgentRow {
  public String agentId;

  @Nullable public String displayName;

  @Nullable public String labels;

  public String status;

  /** Executor slots. Cosmetic for Titan (doc-27 G5). */
  public int numExecutors;

  public int maxConcurrent;
  public int currentTasks;

  @Nullable public String osInfo;

  @Nullable public String javaVersion;

  @Nullable public String capabilitiesJson;

  /** Workspace root for the minted {@code TitanNode}. Null → default {@code /titan}. */
  @Nullable public String remoteFs;

  /** Usage mode: {@code NORMAL} or {@code EXCLUSIVE}. Null → {@code NORMAL}. */
  @Nullable public String usageMode;

  public Instant lastHeartbeat;
  public Instant registeredAt;

  @Nullable public String lastSeenBy;

  @Nullable public String endpointUrl;

  /**
   * Live CPU usage percent (0-100) reported by the worker on its last heartbeat. Null when the
   * worker has not yet heartbeated, or could not sample the value on its JVM (#348).
   */
  @Nullable public Integer cpuPercent;

  /**
   * Live memory usage percent (0-100) reported by the worker on its last heartbeat. Null when
   * unavailable.
   */
  @Nullable public Integer memoryPercent;

  /**
   * Live workspace-volume disk usage percent (0-100) reported by the worker on its last heartbeat.
   * Null when unavailable.
   */
  @Nullable public Integer diskPercent;
}
