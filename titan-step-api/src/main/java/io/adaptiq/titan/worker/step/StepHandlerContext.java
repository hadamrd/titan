package io.adaptiq.titan.worker.step;

import java.lang.System.Logger;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The only worker state a {@link StepHandlerProvider} receives at discovery (design/42 §4.2).
 *
 * <p>Narrow on purpose (design/42 §5.2): a cache root for fetched library checkouts, the running
 * SPI version, and a logger — and nothing else. No DB handle, no controller channel, no credential
 * store. A provider has nothing wider to reach for because there is nothing wider here to import.
 *
 * <p>Kept JDK-only (a {@link java.lang.System.Logger}, not SLF4J) so the thin {@code
 * titan-step-api} module stays dependency-free.
 *
 * @param libraryCacheRoot the directory the {@code libraryCall} handler caches fetched library
 *     checkouts under (one subdir per {@code <git-url>@<resolved-sha>} — design/53).
 * @param apiVersion the running worker's step-SPI generation — {@link StepApi#VERSION}
 * @param logger a JDK {@link java.lang.System.Logger} a provider may use during {@code handlers()}
 */
public record StepHandlerContext(Path libraryCacheRoot, int apiVersion, Logger logger) {

  public StepHandlerContext {
    Objects.requireNonNull(libraryCacheRoot, "libraryCacheRoot");
    Objects.requireNonNull(logger, "logger");
  }
}
