package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A {@link DiscoverySource} that watches a directory on the controller's filesystem — the
 * zero-network discovery vehicle (local-rig dev, and the headline integration-test path). Walks
 * {@link #baseDir} for files matching {@link #pathPattern} (glob syntax).
 *
 * <p>Each match becomes a {@link DiscoveredFile} whose {@code commitSha} is the SHA-256 of the file
 * content — so the {@code discovery_events} dedup constraint distinguishes a changed file from an
 * unchanged one across polls.
 */
public class LocalDirectorySource extends DiscoverySource {

  private static final Logger LOGGER = Logger.getLogger(LocalDirectorySource.class.getName());

  private final String name;
  private final String baseDir;
  private String pathPattern = "**/titan-pipeline.yml";

  public LocalDirectorySource(@NonNull String name, @NonNull String baseDir) {
    this.name = name;
    this.baseDir = baseDir;
  }

  @NonNull
  @Override
  public String getName() {
    return name;
  }

  @NonNull
  @Override
  public String getType() {
    return "localDirectory";
  }

  @NonNull
  public String getBaseDir() {
    return baseDir;
  }

  @NonNull
  public String getPathPattern() {
    return pathPattern;
  }

  public void setPathPattern(@Nullable String pathPattern) {
    this.pathPattern =
        (pathPattern == null || pathPattern.isBlank()) ? "**/titan-pipeline.yml" : pathPattern;
  }

  @NonNull
  @Override
  public List<DiscoveredFile> scan() {
    Path base = Path.of(baseDir);
    if (!Files.isDirectory(base)) {
      LOGGER.log(
          Level.FINE,
          "[titan] discovery source ''{0}'': baseDir not a directory: {1}",
          new Object[] {name, baseDir});
      return List.of();
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pathPattern);
    List<DiscoveredFile> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(base)) {
      walk.filter(Files::isRegularFile)
          .forEach(
              p -> {
                Path rel = base.relativize(p);
                if (!matcher.matches(rel)) {
                  return;
                }
                Path parent = rel.getParent();
                String repo =
                    parent != null
                        ? parent.getFileName().toString()
                        : base.getFileName().toString();
                found.add(new DiscoveredFile(p.toAbsolutePath().toString(), repo, null, sha256(p)));
              });
    } catch (IOException | RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] discovery source '" + name + "': scan of " + baseDir + " failed",
          e);
      return List.of();
    }
    return found;
  }

  @NonNull
  @Override
  public byte[] read(@NonNull String path) {
    try {
      return Files.readAllBytes(Path.of(path));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read discovered file: " + path, e);
    }
  }

  /** SHA-256 hex of the file content — the content revision for the dedup constraint. */
  @NonNull
  private static String sha256(@NonNull Path file) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new UncheckedIOException(
          "Failed to hash discovered file: " + file,
          e instanceof IOException io ? io : new IOException(e));
    }
  }
}
