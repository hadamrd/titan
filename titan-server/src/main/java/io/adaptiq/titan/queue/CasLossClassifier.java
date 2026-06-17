package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.CasLostException;
import java.util.Locale;

/**
 * Recognises CAS-loss signals so {@link QueueProcessor#handleAdvance} can treat them as benign
 * races (re-enqueue, do not fail-close). Two shapes are accepted:
 *
 * <ul>
 *   <li>{@link CasLostException} thrown explicitly by a call site that needs to escape its frame.
 *   <li>Any {@link Throwable} in the cause chain whose message names {@code compareAndSetStatus}
 *       (the JDBI shape observed live on builds 14 / 29 / 30 — issue #911).
 * </ul>
 *
 * <p>Extracted from {@link QueueProcessor} so the host stays under the design 67 line cap.
 */
final class CasLossClassifier {

  private CasLossClassifier() {}

  /** True if the exception (or any cause within 8 hops) is a benign CAS race. */
  static boolean isBenign(@NonNull Throwable e) {
    Throwable t = e;
    for (int hops = 0; t != null && hops < 8; hops++, t = t.getCause()) {
      if (t instanceof CasLostException) return true;
      String msg = t.getMessage();
      if (msg != null && msg.toLowerCase(Locale.ROOT).contains("compareandsetstatus")) return true;
    }
    return false;
  }
}
