package io.adaptiq.titan.flow.artifact;

import java.nio.file.Path;
import java.util.Map;

/**
 * The built-in {@link ArtifactStoreProvider} for the filesystem backend — {@code kind = "fs"}, the
 * Titan default (design/41 E2). Discovered via {@code META-INF/services}.
 */
public final class FilesystemArtifactStoreProvider implements ArtifactStoreProvider {

  /** Config key — the absolute path of the artifacts root directory. */
  public static final String CONFIG_ROOT = "root";

  @Override
  public String kind() {
    return "fs";
  }

  @Override
  public ArtifactStore create(Map<String, String> config) {
    String root = config == null ? null : config.get(CONFIG_ROOT);
    if (root == null || root.isBlank()) {
      throw new IllegalArgumentException(
          "filesystem artifact store requires config key '"
              + CONFIG_ROOT
              + "' — the artifacts root directory");
    }
    return new FilesystemArtifactStore(Path.of(root));
  }
}
