package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.store.TestResultDao.TestSummary;
import java.util.List;

/**
 * Wire shape for {@code GET /api/v1/builds/{buildId}/tests}: {@code items} is the requested window,
 * {@code total} is the full row count for the build (so the UI can render its pagination footer
 * without iterating windows), and {@code summary} carries the passed/failed/skipped aggregate over
 * the whole build (not just the page) so the top-of-panel counters stay correct regardless of the
 * slice currently in view.
 */
public record TestResultsPage(List<TestRowDto> items, int total, TestSummary summary) {}
