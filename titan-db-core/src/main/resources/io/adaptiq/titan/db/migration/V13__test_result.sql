-- Titan schema V13 — per-test-case results for a build's `junit` step.
--
-- Issue #298 (storage prerequisite for the #296 UI test-results panel and the
-- upcoming GET /api/v1/builds/{id}/tests endpoint). Today's `junit` step (see
-- titan-worker/JUnitStepHandler) parses surefire XML and publishes a tally as
-- step outputs + archives the raw XMLs under titan/test-reports/ — but the
-- individual test cases are nowhere addressable on the controller. This table
-- makes them addressable on the server.
--
-- One row per <testcase> across the build's reports. Idempotency belongs to
-- the writer (the worker drops + re-inserts a build's rows on re-run): the
-- table records observations, not a primary key on (build, suite, class, name)
-- — the same test name can legitimately repeat across suites/parametrised
-- variants, and the natural-key collision space here is not worth the
-- migration foot-gun.
--
-- Portable across H2 (embedded/test) and PostgreSQL (production). VARCHAR for
-- text, TIMESTAMP for times, BIGINT GENERATED ALWAYS AS IDENTITY — exactly the
-- V1 conventions (no BIGSERIAL, no TIMESTAMPTZ, no TEXT, no now()).

CREATE TABLE titan.test_result (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id        BIGINT       NOT NULL,
    node_id         VARCHAR(64)  NOT NULL,
    -- The <testsuite name=…> the case was reported under.
    suite           VARCHAR(512) NOT NULL,
    -- The <testcase classname=…>. JUnit XML keeps these separate even though
    -- many reporters fold them; we preserve the wire shape so the API can
    -- group either way without losing information.
    class_name      VARCHAR(512) NOT NULL,
    name            VARCHAR(512) NOT NULL,
    status          VARCHAR(16)  NOT NULL
                    CHECK (status IN ('PASSED','FAILED','SKIPPED')),
    duration_ms     BIGINT       NOT NULL DEFAULT 0,
    -- The <failure message=…> / <error message=…> + nested stack, joined.
    -- NULL for PASSED / SKIPPED. Plain VARCHAR (no LOB) — keeps it portable
    -- and stays under H2's default page-row limits in practice (stack traces
    -- > a few KB are exceptional and get truncated by the writer).
    failure_message VARCHAR,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_test_result_build
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE
);

-- The two queries the API and the UI panel will run.
--   listing  → ORDER BY id LIMIT/OFFSET on (build_id)
--   summary  → COUNT(*) GROUP BY status on (build_id, status)
CREATE INDEX idx_test_result_build        ON titan.test_result(build_id);
CREATE INDEX idx_test_result_build_status ON titan.test_result(build_id, status);
