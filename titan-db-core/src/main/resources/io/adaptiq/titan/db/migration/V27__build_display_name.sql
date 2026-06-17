-- V27 (#762): `setBuildName` controller-native step backing column.
--
-- An optional human-friendly name for a build, set by the `setBuildName:` pipeline
-- step via SetBuildNameResolver. Last-write wins across multiple steps in the same
-- pipeline. Nullable + no default so existing builds remain unaffected and the UI
-- can fall back to "#<buildNumber>" when absent (UI follow-up).
--
-- Capped at 200 characters — over-length names are rejected at resolve time, never
-- silently truncated (the brief explicitly forbids silent truncation).
ALTER TABLE titan.builds ADD COLUMN display_name VARCHAR(200);
