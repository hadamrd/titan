package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Request body for {@code POST /api/v1/pulsar/sources} — register a Pulsar SCM node connection
 * (#1283). Field names match the {@code RegisterPulsarSourceRequest} the UI's Integrations
 * "Connect" form sends.
 *
 * @param nodeUrl the Pulsar node base URL (e.g. {@code https://pulsar.example.com}); required, must
 *     be a well-formed http(s) URL — validated by the API, not Jackson
 * @param nodeName an optional human label for the source; null/blank → stored as null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterPulsarSourceRequest(String nodeUrl, @Nullable String nodeName) {}
