package io.adaptiq.titan.worker.step.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 42-T — unit coverage for {@link CredentialsAugmenter} (design/39, refactored per design/42 §4.9).
 * The 42-X agent flagged that no augmenter unit test existed; this is it. The augmenter is
 * exercised directly against a {@link StepExecutionContext} built from a plain-data credential
 * bundle — no controller, no sealing, no DB.
 */
class CredentialsAugmenterTest {

  private static StepExecutionContext ctx(Path workspace, Map<String, Object> bundle) {
    return new StepExecutionContext(
        "sh", new HashMap<>(), new HashMap<>(), workspace, 1L, "node-1", bundle);
  }

  @Test
  void anEmptyBundleIsAPureNoOp(@TempDir Path workspace) {
    StepExecutionContext c = ctx(workspace, Map.of());
    new CredentialsAugmenter().augment(c);
    assertTrue(c.env().isEmpty(), "no credentials: ⇒ no env added");
    assertTrue(c.maskValues().isEmpty(), "no credentials: ⇒ nothing to mask");
    assertTrue(c.postStepActions().isEmpty(), "no credentials: ⇒ no cleanup registered");
  }

  @Test
  void mergesTheBundleEnvOntoTheStepEnv(@TempDir Path workspace) {
    Map<String, Object> bundle = Map.of("env", Map.of("API_TOKEN", "s3cr3t", "REGION", "eu"));
    StepExecutionContext c = ctx(workspace, bundle);
    new CredentialsAugmenter().augment(c);
    assertEquals("s3cr3t", c.env().get("API_TOKEN"));
    assertEquals("eu", c.env().get("REGION"));
  }

  @Test
  void registersMaskValuesFromTheBundle(@TempDir Path workspace) {
    Map<String, Object> bundle = Map.of("maskSecrets", List.of("s3cr3t", "hunter2"));
    StepExecutionContext c = ctx(workspace, bundle);
    new CredentialsAugmenter().augment(c);
    assertTrue(c.maskValues().contains("s3cr3t"));
    assertTrue(c.maskValues().contains("hunter2"));
  }

  @Test
  void materialisesSecretFilesAndBindsThemToEnv(@TempDir Path workspace) {
    String contentB64 =
        Base64.getEncoder().encodeToString("PRIVATE-KEY-BYTES".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> file =
        Map.of("variable", "SSH_KEY_FILE", "fileName", "id_rsa", "contentBase64", contentB64);
    StepExecutionContext c = ctx(workspace, Map.of("secretFiles", List.of(file)));
    new CredentialsAugmenter().augment(c);

    String bound = c.env().get("SSH_KEY_FILE");
    assertTrue(
        bound != null && bound.contains("id_rsa"),
        "the secret file path must be bound to its env variable");
    assertTrue(Files.exists(Path.of(bound)), "the secret file must be materialised on disk");
  }

  @Test
  void thePostStepActionWipesTheMaterialisedSecretFiles(@TempDir Path workspace) throws Exception {
    String contentB64 =
        Base64.getEncoder().encodeToString("PRIVATE-KEY-BYTES".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> file =
        Map.of("variable", "SSH_KEY_FILE", "fileName", "id_rsa", "contentBase64", contentB64);
    StepExecutionContext c = ctx(workspace, Map.of("secretFiles", List.of(file)));
    new CredentialsAugmenter().augment(c);

    Path materialised = Path.of(c.env().get("SSH_KEY_FILE"));
    assertTrue(Files.exists(materialised));
    // Running the registered post-step action wipes the secret file (design/39 §3).
    assertFalse(c.postStepActions().isEmpty(), "a secret-file wipe must be registered");
    c.postStepActions().forEach(Runnable::run);
    assertFalse(Files.exists(materialised), "the post-step action must unlink the secret file");
  }

  @Test
  void aSecretFileNameMustNotEscapeTheCredentialsDirectory(@TempDir Path workspace) {
    String contentB64 = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> file =
        Map.of("variable", "ESCAPED", "fileName", "../../etc/evil", "contentBase64", contentB64);
    StepExecutionContext c = ctx(workspace, Map.of("secretFiles", List.of(file)));
    new CredentialsAugmenter().augment(c);
    // A path-escaping fileName is silently skipped — it must not bind, and nothing escapes.
    assertFalse(
        c.env().containsKey("ESCAPED"),
        "a fileName escaping the credentials dir must not be materialised");
  }
}
