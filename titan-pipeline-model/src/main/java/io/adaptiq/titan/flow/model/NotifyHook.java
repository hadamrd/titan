package io.adaptiq.titan.flow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code notify:} lifecycle hook (#245). A declarative, fire-and-forget post-terminal hook —
 * the orchestrator fires it once the build (or, at stage scope, the stage) has reached its terminal
 * state, regardless of which step ran or whether any step ran at all. Declared at pipeline root or
 * on a stage; the same shape both places.
 *
 * <p>Today only one {@link #type} is shipped — {@code webhook}: a plain HTTP POST to {@link #url}
 * carrying a small JSON envelope (build id, status, pipeline/stage name). {@code slack} (and any
 * other auth-bearing sink) is tracked as a follow-up: a Slack inbound-webhook URL carries a
 * workspace token, so it must route through {@code CredentialsService} via {@link #credentialsId},
 * never as an inline literal in {@link #url}. Until that wiring lands, {@code type: slack} is
 * rejected at parse time. The {@code credentialsId} field is reserved here so the grammar surface
 * does not move when slack lands.
 */
public final class NotifyHook {

  /** The hook kind. Today only {@code "webhook"} is shipped. */
  @NonNull private String type = "webhook";

  /**
   * The terminal-state predicates this hook fires on: {@code success}, {@code failure}, or {@code
   * always}. {@code always} fires regardless of result. Empty means {@code always} (the safe
   * default — a declared hook with no filter is meant to fire).
   */
  @NonNull private List<String> on = new ArrayList<>();

  /** The webhook target URL. Required for {@code type: webhook}. */
  @Nullable private String url;

  /**
   * The credentials-store id for auth-bearing hooks. <strong>Required</strong> for {@code type:
   * slack} (#358) — the stored secret is the Slack inbound-webhook URL; resolved at dispatch time
   * by {@code CredentialsService}, never inline. Optional for {@code type: webhook}. NEVER store a
   * literal token / bearer string / URL here; this is the id of a secret resolved by {@code
   * CredentialsService} at dispatch time (design/39, CONSTITUTION §6).
   */
  @Nullable private String credentialsId;

  /**
   * Optional Slack channel override (e.g. {@code #deploys}). Plain display string — NOT a secret.
   * Ignored for non-Slack hook types. When unset, Slack uses the inbound-webhook's default channel.
   */
  @Nullable private String channel;

  public NotifyHook() {}

  @JsonCreator
  public NotifyHook(
      @JsonProperty("type") @NonNull String type,
      @JsonProperty("on") @NonNull List<String> on,
      @JsonProperty("url") @Nullable String url,
      @JsonProperty("credentialsId") @Nullable String credentialsId,
      @JsonProperty("channel") @Nullable String channel) {
    this.type = type;
    this.on = new ArrayList<>(on);
    this.url = url;
    this.credentialsId = credentialsId;
    this.channel = channel;
  }

  @NonNull
  public String getType() {
    return type;
  }

  public void setType(@NonNull String type) {
    this.type = type;
  }

  @NonNull
  public List<String> getOn() {
    return on;
  }

  public void setOn(@NonNull List<String> on) {
    this.on = on;
  }

  @Nullable
  public String getUrl() {
    return url;
  }

  public void setUrl(@Nullable String url) {
    this.url = url;
  }

  @Nullable
  public String getCredentialsId() {
    return credentialsId;
  }

  public void setCredentialsId(@Nullable String credentialsId) {
    this.credentialsId = credentialsId;
  }

  @Nullable
  public String getChannel() {
    return channel;
  }

  public void setChannel(@Nullable String channel) {
    this.channel = channel;
  }

  /**
   * True if this hook should fire for a build/stage whose terminal status is {@code FAILED}. Empty
   * {@code on:} or an {@code always} entry both match.
   */
  public boolean firesOnFailure() {
    return on.isEmpty() || on.contains("always") || on.contains("failure");
  }

  /**
   * True if this hook should fire for a build/stage whose terminal status is {@code SUCCESS}. Empty
   * {@code on:} or an {@code always} entry both match.
   */
  public boolean firesOnSuccess() {
    return on.isEmpty() || on.contains("always") || on.contains("success");
  }

  /**
   * True if this hook should fire on a fail→success transition (#1102 — recovery). Note that this
   * is INDEPENDENT of {@link #firesOnSuccess()}: a hook declared {@code on: [recovery]} does NOT
   * fire on every green build, only on the transition out of a previous failure. Empty {@code on:}
   * or an {@code always} entry both match — those are the "fire on everything" sentinels and
   * recovery is part of "everything".
   *
   * <p>The orchestrator decides at the call site whether the current build IS a recovery (by
   * inspecting the previous finished build of the same job); this predicate only answers "did the
   * user opt in to recovery messages on this hook".
   */
  public boolean firesOnRecovery() {
    return on.isEmpty() || on.contains("always") || on.contains("recovery");
  }
}
