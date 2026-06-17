package io.adaptiq.titan.flow.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ArtifactStoreRegistry} — backend discovery via {@code ServiceLoader} (design/41
 * §8.6). The built-in filesystem provider must be found, configured, and unknown kinds rejected
 * with a diagnostic that lists what <em>is</em> registered.
 */
class ArtifactStoreRegistryTest {

  @Test
  void resolvesTheBuiltInFilesystemBackend(@TempDir Path root) {
    ArtifactStore store =
        ArtifactStoreRegistry.resolve(
            "fs", Map.of(FilesystemArtifactStoreProvider.CONFIG_ROOT, root.toString()));
    assertInstanceOf(FilesystemArtifactStore.class, store);
    assertEquals("fs", store.kind());
  }

  @Test
  void theFilesystemBackendIsDiscoveredViaServiceLoader() {
    assertTrue(
        ArtifactStoreRegistry.kinds().contains("fs"),
        "the built-in 'fs' provider must be discovered from META-INF/services");
  }

  @Test
  void anUnknownKindIsRejectedWithADiagnostic() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ArtifactStoreRegistry.resolve("quantum-blob", Map.of()));
    assertTrue(e.getMessage().contains("quantum-blob"), e.getMessage());
    assertTrue(e.getMessage().contains("fs"), "the error lists the backends that are registered");
  }

  @Test
  void theFilesystemBackendRejectsMissingRootConfig() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> ArtifactStoreRegistry.resolve("fs", Map.of()));
    assertTrue(
        e.getMessage().contains(FilesystemArtifactStoreProvider.CONFIG_ROOT), e.getMessage());
  }
}
