---
name: schema-migration
applies-to: engine-agent
related-design-doc: design/07-state-and-storage.md
---

## What this is

Add a Flyway migration that evolves the Release Flow schema. Used
when a new table, column, index, or constraint is needed.

## When to use

- New entity needs a table.
- Existing table needs a new column / index / constraint.
- Big tables (e.g. `deployments`) need ALTER (must use ALTER, not
  table rewrite — even at our scale, `ALTER TABLE deployments` is a
  multi-second blocker).

## NEVER do

- **Edit a shipped V<n>__*.sql file.** Once V1 is in any production
  install, V1 is immutable. New change = V2.
- **Use H2-specific or Postgres-specific syntax** without checking
  cross-compat. Avoid `JSONB`, `JSON` types, array columns, ENUMs.
  JSON-shaped data is `CLOB`/`TEXT`.
- **Drop a column in a single migration if rows might still write to
  it.** Use a two-phase pattern: V<n> stops writing (deploy + observe);
  V<n+1> drops the column.
- **Skip the index for a hot-path query.** If you're adding a column
  that gates a where-clause used on every dashboard render, add the
  index in the same migration.

## Procedure

### 1. Choose the next version number

Find the highest existing `V<n>__*.sql` under
`src/main/resources/io/adaptiq/titan/db/migration/`.
Add 1.

```bash
ls -1 src/main/resources/io/adaptiq/titan/db/migration/
# V1__init.sql
# V2__add_dependsOn_to_components.sql   ← new
```

### 2. Write the migration

File: `V<n>__<snake_case_description>.sql`.

Conventions:
- Header comment: what this migration does + why + rollback note ("no
  rollback; restore from backup").
- Use portable SQL (H2 + PostgreSQL).
- Identity columns: `BIGINT GENERATED ALWAYS AS IDENTITY`.
- Time columns: `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`.
- JSON columns: `CLOB` with `_json` suffix in the column name.
- Foreign keys: spell out as `CONSTRAINT fk_<table>_<col>`.
- Indexes: `CREATE INDEX idx_<table>_<cols>`.
- Multi-statement migrations: separate with semicolons; Flyway
  handles batching.

Skeleton for adding a column to an existing table:

```sql
-- V<n>__<description>.sql
--
-- Adds <column> to <table> for <reason>.
--
-- Rollback: not supported; restore DB from backup if needed.

ALTER TABLE <table>
    ADD COLUMN <column> <TYPE> <NULL_BEHAVIOR>;

-- Backfill if needed:
UPDATE <table> SET <column> = <default> WHERE <column> IS NULL;

-- Index if the new column is used in WHERE / ORDER BY:
CREATE INDEX idx_<table>_<column> ON <table>(<column>);
```

Skeleton for adding a new table — see `V1__init.sql` for the canonical
shape.

### 3. Update DAOs

Every column in the migration must map to a Row field + DAO method.
Run [dao-skill.md](dao-skill.md) for guidance.

### 4. Update tests

If the new column has non-trivial defaults or backfill logic, add a
test that boots Flyway + verifies the column shape:

```java
try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT <column> FROM <table> LIMIT 1")) {
    assertNotNull(rs.getMetaData());
}
```

For column-add migrations on existing tables that contain rows, also
test the backfill (boot Flyway against a DB pre-populated with V<n-1>
schema state, then verify the column has the right values after V<n>
applies).

### 5. Boot-test it

Boot the rig (`task dev:titan`) and confirm the migration applies — the
schema log line shows version N+1.

## Worked example

V1 migration (`V1__init.sql`): the entire initial schema. 22 tables,
indexed for the design's hot paths. Single migration since v1 hasn't
shipped yet.

A future V2 example (`V2__add_skip_reason_to_deployments.sql`):

```sql
ALTER TABLE deployments ADD COLUMN skip_reason VARCHAR(255);
```

Already shipped inline in V1 because v1 hasn't released; if it had,
this would be the V2 form.

## Common variations / gotchas

- **JSON columns**: store as `CLOB`; query in Java with Jackson. Don't
  rely on database JSON operators.
- **Big-table ALTER**: `deployments` will eventually have millions of
  rows. Adding a NOT NULL column requires backfill before the NOT NULL
  constraint can attach. Two-phase pattern: V<n> adds nullable +
  backfill; V<n+1> adds NOT NULL.
- **Composite indexes order**: column order in `CREATE INDEX (a, b, c)`
  matters; lead with the column that's most-selective for your query.
- **CHECK constraints**: H2 + Postgres both support `CHECK (status IN
  ('a', 'b'))`. Use them for enum-like columns; they catch bad inserts
  at the DB layer.

## Cross-references

- [dao-skill.md](dao-skill.md) — wire the new column into DAOs.
- `task dev:titan` — verify the migration applies on a fresh boot.
- design/07-state-and-storage.md
