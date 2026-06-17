package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.dto.AuditRetentionPolicyDto;
import io.adaptiq.titan.api.dto.SetAuditRetentionPolicyRequest;
import io.adaptiq.titan.store.TitanStores;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link AuditPolicyHandler} request validation (#1104).
 *
 * <p>Every case here rejects the request <em>before</em> any store call, so the handler is
 * constructed with a {@code null} {@code TitanStores} — touching the store would NPE and fail the
 * test, which is exactly the guarantee we want: validation precedes persistence.
 *
 * <p>The headline adversarial case is the <strong>0-day misconfig guard</strong>: a 0-day policy
 * would purge the kind's entire history, so the endpoint must answer 400, never silently accept it.
 */
class AuditPolicyHandlerTest {

  private final AuditPolicyHandler handler = new AuditPolicyHandler(null);

  @Test
  void setRejectsZeroDayPolicyWith400() {
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> handler.set(new SetAuditRetentionPolicyRequest("PAT_CREATE", 0)));
    assertTrue(ex.getMessage().contains("maxAgeDays"), "message names the offending field");
  }

  @Test
  void setRejectsNegativeDayPolicyWith400() {
    assertThrows(
        ApiBadRequestException.class,
        () -> handler.set(new SetAuditRetentionPolicyRequest("RBAC_CHECK", -1)));
  }

  @Test
  void setRejectsMissingMaxAgeDays() {
    assertThrows(
        ApiBadRequestException.class,
        () -> handler.set(new SetAuditRetentionPolicyRequest("JOB_CREATE", null)));
  }

  @Test
  void setRejectsUnknownKind() {
    // A typo'd kind must 400 — not create a dead policy row the job never applies.
    ApiBadRequestException ex =
        assertThrows(
            ApiBadRequestException.class,
            () -> handler.set(new SetAuditRetentionPolicyRequest("NOT_A_REAL_ACTION", 30)));
    assertTrue(ex.getMessage().contains("kind"));
  }

  @Test
  void setRejectsBlankKind() {
    assertThrows(
        ApiBadRequestException.class,
        () -> handler.set(new SetAuditRetentionPolicyRequest("  ", 30)));
  }

  @Test
  void setRejectsNullBody() {
    assertThrows(ApiBadRequestException.class, () -> handler.set(null));
  }

  @Test
  void setRejectsHorizonAboveSanityCap() {
    assertThrows(
        ApiBadRequestException.class,
        () -> handler.set(new SetAuditRetentionPolicyRequest("JOB_CREATE", 9_999_999)));
  }

  /**
   * GET surface coverage (#1196 review): {@code list()} was previously untested — the null-store
   * handler above would NPE on any read. Drive it against a real H2-backed store so the DAO →
   * {@link AuditRetentionPolicyDto} round-trip is genuinely exercised.
   */
  @Test
  void listRoundTripsPersistedPolicyRows() {
    TitanStores stores = FakeTitanStores.create();
    AuditPolicyHandler liveHandler = new AuditPolicyHandler(stores);
    // A distinct override on top of the V42 seed so we can assert exact field round-trip.
    stores.auditRetentionPolicy().upsert("JOB_CREATE", 42);

    List<AuditRetentionPolicyDto> all = liveHandler.list();

    AuditRetentionPolicyDto jobCreate =
        all.stream()
            .filter(d -> "JOB_CREATE".equals(d.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("JOB_CREATE override missing from list()"));
    assertEquals(42, jobCreate.maxAgeDays(), "the override horizon round-trips through the DTO");

    // The seeded '*' default is also visible — list() returns the whole persisted set.
    assertTrue(
        all.stream().anyMatch(d -> "*".equals(d.kind()) && d.maxAgeDays() == 90),
        "the '*' default seeded by V42 is listed");
  }
}
