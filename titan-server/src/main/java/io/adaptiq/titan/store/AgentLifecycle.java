package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Thin orchestrator that pairs {@link AgentDao} register/delete writes with their {@link
 * AgentEventsDao} lifecycle-log emissions (closes #714).
 *
 * <p>Lives next to the DAOs (not on them) because a DAO writes one table; pairing the {@code
 * agents} row mutation with the {@code agent_events} row insert is a composite operation. Callers
 * that mutate agent lifecycle SHOULD go through this service so the event log stays faithful;
 * callers that only need the agents-table side (heartbeat, status flips, etc.) still hit {@link
 * AgentDao} directly.
 *
 * <p>The event-log writes intentionally do not run in the same JDBI transaction as the agents
 * write: a dropped event is recoverable noise (the timeline misses one row); a failed event insert
 * must not prevent a register/drop from succeeding because operability of the cluster outranks
 * timeline completeness.
 */
public final class AgentLifecycle {

  private final AgentDao agents;
  private final AgentEventsDao events;

  public AgentLifecycle(@NonNull AgentDao agents, @NonNull AgentEventsDao events) {
    this.agents = agents;
    this.events = events;
  }

  /** Convenience factory for the typical caller — pull both DAOs from a {@link TitanStores}. */
  @NonNull
  public static AgentLifecycle of(@NonNull TitanStores stores) {
    return new AgentLifecycle(stores.agents(), stores.agentEvents());
  }

  /**
   * Register an agent and emit a deduplicated {@code JOINED} event (5-minute window — see {@link
   * AgentEventsDao#recordJoined}). Re-registering the same agent inside the window keeps the
   * agents-table refresh (status='ONLINE', last_heartbeat=now()) but suppresses the event row.
   */
  public void register(
      @NonNull String agentId, String displayName, String labels, int numExecutors) {
    agents.register(agentId, displayName, labels, numExecutors);
    events.recordJoined(agentId);
  }

  /**
   * Hard-delete an agent and emit a {@code LEFT} event BEFORE the delete, because the FK on {@code
   * agent_events.agent_id} cascades the events away with the agent row — recording first would lose
   * its own row.
   *
   * <p>NOTE: with the current {@code ON DELETE CASCADE} schema, the just-inserted {@code LEFT} row
   * is removed together with the agent. That is intentional v1 behaviour: a hard-deleted agent has
   * no history to show on the timeline anyway. Callers that want a LEFT event to survive should
   * call {@link #markOffline} instead — {@code markOffline} keeps the agents row around
   * (status='OFFLINE') so the lifecycle trail persists.
   */
  public void delete(@NonNull String agentId) {
    events.recordLeft(agentId);
    agents.delete(agentId);
  }

  /**
   * Mark an agent OFFLINE and emit a {@code LEFT} event. Preferred over {@link #delete} for the
   * soft-departure case (drain timeout, reaper, controlled shutdown) so the timeline keeps a
   * visible trail.
   */
  public void markOffline(@NonNull String agentId) {
    agents.markOffline(agentId);
    events.recordLeft(agentId);
  }
}
