package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.scm_webhook_event} — the durable ingestion log for
 * SCM webhook deliveries (issue #1129).
 *
 * <p>One row per (provider, deliveryId). Inserted by the webhook endpoint BEFORE the dispatch
 * handler runs; transitioned to {@code PROCESSED} on success or {@code FAILED} on terminal error.
 * The {@code PENDING} state with a scheduled {@code nextAttemptAt} is the durable-retry hand-off
 * point with the timer subsystem.
 *
 * <p>Public fields per project convention (JDBI {@code @RegisterFieldMapper}).
 */
public class ScmWebhookEventRow {
  public long id;
  public String provider;
  public String deliveryId;
  public String eventType;
  public Instant receivedAt;

  /** {@code PENDING} | {@code PROCESSED} | {@code FAILED}. */
  public String status;

  public int attempts;
  public Instant nextAttemptAt;
  public String lastError;

  /** Raw JSON body as received from the SCM. */
  public String payload;

  /** Optional verifier signature (e.g. GitHub {@code X-Hub-Signature-256}). */
  public String signature;
}
