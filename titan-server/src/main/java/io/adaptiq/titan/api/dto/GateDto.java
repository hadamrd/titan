package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * Wire-format projection of a manual-approval gate as exposed by {@link
 * io.adaptiq.titan.api.GatesApi} (closes #295 backend).
 *
 * <p>The {@code state} field is the {@code flow_nodes.status} string ("RUNNING" while awaiting,
 * terminal states otherwise — though the GET endpoint only ever lists RUNNING gates). {@code
 * awaitingSince} is the instant the gate's flow-node entered RUNNING.
 *
 * <p>Audit JSON (decided-by, decided-at) stays internal — never leaked over this resource.
 */
@JsonInclude(Include.NON_NULL)
public record GateDto(
    String nodeId,
    String name,
    List<String> approvers,
    String state,
    @Nullable Instant awaitingSince) {}
