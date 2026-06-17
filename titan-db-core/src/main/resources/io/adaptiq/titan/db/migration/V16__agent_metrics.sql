-- Titan schema V16 — worker live host metrics on heartbeat (#348).
--
-- A worker now samples CPU / memory / disk usage on each heartbeat and stamps
-- them into the agents row. All three are integer percents (0-100), nullable
-- when the host cannot supply a reading (e.g. JVM not providing CPU load,
-- workspace filesystem 0-total). The Workers grid in the UI renders them in
-- the existing CPU/MEM/DISK bars (titan-ui/src/routes/workers.tsx).
--
-- INT (not SMALLINT) for H2/PostgreSQL portability and to leave headroom; all
-- three nullable so a heartbeat from an older worker, or a sampler that has
-- no signal, stays NULL (the UI renders an em-dash on null). Separate ALTER
-- statements — portable across H2 and PostgreSQL.

ALTER TABLE titan.agents ADD COLUMN cpu_percent    INT;
ALTER TABLE titan.agents ADD COLUMN memory_percent INT;
ALTER TABLE titan.agents ADD COLUMN disk_percent   INT;
