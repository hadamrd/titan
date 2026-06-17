package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.adaptiq.titan.auth.GroupMappingValidator.InvalidGroupMappingException;
import org.junit.jupiter.api.Test;

/**
 * Pure-function coverage for {@link GroupMappingValidator} — closes #1136.
 *
 * <p>Follows the testing manifesto: one happy-path assertion per validator, then one adversarial
 * assertion per failure-class. The validator is the single point where the wire shape becomes a
 * known enum, so every bad shape that could reach the REST layer is exercised here.
 */
class GroupMappingValidatorTest {

  // ── group_path ─────────────────────────────────────────────────────────────

  @Test
  void groupPath_happy() {
    assertEquals(
        "/acme/platform-admins", GroupMappingValidator.validateGroupPath("/acme/platform-admins"));
    assertEquals("/x", GroupMappingValidator.validateGroupPath("/x"));
    assertEquals("/A_b-C/d_e", GroupMappingValidator.validateGroupPath("  /A_b-C/d_e  "));
  }

  @Test
  void groupPath_null_or_blank_rejected() {
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateGroupPath(null));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateGroupPath(""));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateGroupPath("   "));
  }

  @Test
  void groupPath_missing_leading_slash_rejected() {
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("acme/admins"));
  }

  @Test
  void groupPath_trailing_slash_rejected() {
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme/admins/"));
  }

  @Test
  void groupPath_double_slash_rejected() {
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme//admins"));
  }

  @Test
  void groupPath_illegal_chars_rejected() {
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme/ad mins"));
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme/admins?x"));
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme/admins;drop"));
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateGroupPath("/acme/ünicode"));
  }

  @Test
  void groupPath_over_length_rejected() {
    String path = "/" + "a".repeat(600);
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateGroupPath(path));
  }

  // ── role ───────────────────────────────────────────────────────────────────

  @Test
  void role_happy() {
    assertEquals("ADMIN", GroupMappingValidator.validateRole("admin"));
    assertEquals("MAINTAINER", GroupMappingValidator.validateRole("Maintainer"));
    assertEquals("DEVELOPER", GroupMappingValidator.validateRole(" DEVELOPER "));
    assertEquals("VIEWER", GroupMappingValidator.validateRole("viewer"));
  }

  @Test
  void role_null_or_blank_rejected() {
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateRole(null));
    assertThrows(InvalidGroupMappingException.class, () -> GroupMappingValidator.validateRole(""));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateRole("  "));
  }

  @Test
  void role_unknown_rejected() {
    // From the manifesto: discriminator must be a known enum constant.
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateRole("superuser"));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateRole("READ_JOB"));
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateRole("admin;DROP TABLE"));
  }

  // ── org id ─────────────────────────────────────────────────────────────────

  @Test
  void orgId_happy() {
    assertEquals("acme", GroupMappingValidator.validateOrgId("acme"));
    assertEquals("acme-2025", GroupMappingValidator.validateOrgId("acme-2025"));
    assertEquals("Acme_Inc", GroupMappingValidator.validateOrgId("  Acme_Inc  "));
  }

  @Test
  void orgId_invalid_rejected() {
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateOrgId(null));
    assertThrows(InvalidGroupMappingException.class, () -> GroupMappingValidator.validateOrgId(""));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateOrgId("/acme"));
    assertThrows(
        InvalidGroupMappingException.class, () -> GroupMappingValidator.validateOrgId("acme inc"));
    assertThrows(
        InvalidGroupMappingException.class,
        () -> GroupMappingValidator.validateOrgId("acme/admins"));
  }
}
