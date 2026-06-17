package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.PulsarSourceRow;
import java.time.Instant;

/**
 * Wire representation of a registered Pulsar SCM source (#1283) — the row the Integrations UI card
 * lists and refreshes. Field names match {@code titan-ui/src/api/pulsar.ts}'s {@code
 * PulsarSourceDto} so the card wires with zero impedance mismatch.
 *
 * <p>{@code repoCount} / {@code lastPolledAt} are null until the node has been (re-)probed
 * successfully; null optional fields are omitted from the JSON ({@link
 * JsonInclude.Include#NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PulsarSourceDto(
    long id,
    String nodeUrl,
    String nodeName,
    Integer repoCount,
    Instant lastPolledAt,
    Instant createdAt) {

  @NonNull
  public static PulsarSourceDto from(@NonNull PulsarSourceRow row) {
    return new PulsarSourceDto(
        row.id, row.nodeUrl, row.nodeName, row.repoCount, row.lastPolledAt, row.createdAt);
  }
}
