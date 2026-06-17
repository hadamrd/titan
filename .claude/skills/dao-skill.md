---
name: dao-skill
applies-to: engine-agent
related-design-doc: design/07-state-and-storage.md
---

## What this is

Add a new JDBC DAO + Row class for an entity in the schema, and wire
it into the `Daos` façade. Used 20+ times during Phase A.

## When to use

- A new table is added in a Flyway migration and code needs to read/write it.
- An existing DAO needs a new method (e.g. a new query for the
  dashboard hot path).

## Procedure

### 1. Confirm the schema

The table must exist in `V<n>__*.sql` under
`src/main/resources/io/adaptiq/titan/db/migration/`.
If not, run [schema-migration.md](schema-migration.md) first.

### 2. Write the Row class

Path: `src/main/java/io/adaptiq/titan/db/dao/rows/<Entity>Row.java`

Conventions:
- `public class <Entity>Row` — public mutable POJO.
- Public fields, **no** getters/setters.
- `@Nullable` from `edu.umd.cs.findbugs.annotations` on optional fields.
- Time columns: `java.time.Instant`.
- ID: `long` for auto-generated, `String` for natural.
- JSON-shaped columns: `String` (parsed in service layer, not DAO).

For small/throwaway rows (e.g. join tables, history rows), nest them as
static inner classes inside `rows/Rows.java` instead of standalone files.

### 3. Write the DAO class

Path: `src/main/java/io/adaptiq/titan/db/dao/<Entity>Dao.java`

Skeleton:

```java
package io.adaptiq.titan.db.dao;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.db.dao.rows.<Entity>Row;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** SQL access for {@code <table>}. */
public final class <Entity>Dao {

    private static final String COLS = "id, col1, col2, ...";

    private final DataSource ds;

    <Entity>Dao(@NonNull DataSource ds) {  // package-private — only Daos.java instantiates
        this.ds = ds;
    }

    @NonNull
    public Optional<<Entity>Row> findById(long id) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM <table> WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("<table>.findById failed: " + id, e);
        }
    }

    // List query
    @NonNull
    public List<<Entity>Row> listAll() {
        List<<Entity>Row> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM <table> ORDER BY ...");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw new DaoException("<table>.listAll failed", e);
        }
    }

    // Upsert: try update, if 0 rows fall through to insert
    public long upsert(@NonNull <Entity>Row row) {
        try (Connection c = ds.getConnection()) {
            return upsert(c, row);
        } catch (SQLException e) {
            throw new DaoException("<table>.upsert failed: " + row.naturalKey, e);
        }
    }

    public long upsert(@NonNull Connection c, @NonNull <Entity>Row row) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE <table> SET col1=?, col2=? WHERE natural_key=?")) {
            // ... bind, executeUpdate, return id if affected > 0 ...
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO <table> (col1, col2) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            // ... bind, executeUpdate, getGeneratedKeys ...
        }
    }

    @NonNull
    private static <Entity>Row map(@NonNull ResultSet rs) throws SQLException {
        <Entity>Row r = new <Entity>Row();
        r.id = rs.getLong("id");
        // ... map every column. Use rs.wasNull() after getLong/getInt for nullable numeric cols.
        return r;
    }
}
```

### 4. Wire into Daos.java façade

Add the field, init it in the constructor, and expose a getter.

```java
private final <Entity>Dao <entity>Dao;

private Daos(@NonNull DataSource ds) {
    // ... existing fields ...
    this.<entity>Dao = new <Entity>Dao(ds);
}

@NonNull
public <Entity>Dao <entity>s() {  // pluralized
    return <entity>Dao;
}
```

### 5. Write the test

See [dao-test-pattern.md](dao-test-pattern.md). One test class per DAO,
mirroring `AppDaoTest.java`.

## Worked example

Implementing `EnvironmentDao`:

- Schema: `environments` table in `V1__init.sql:65-75`.
- Row: `src/main/java/io/adaptiq/titan/db/dao/rows/EnvironmentRow.java`
  — fields: `long id`, `long groupId`, `String name`, `int sortOrder`, `@Nullable String tagsJson`.
- DAO: `src/main/java/io/adaptiq/titan/db/dao/EnvironmentDao.java`
  — methods: `listByGroup(long)`, `findByName(String)`, `upsert(...)`,
  `deleteByName(String)`. Connection-overload of `upsert` for transactional
  use.
- Façade: `Daos.java:139` adds `environments()` accessor.
- Test: `EnvironmentDaoTest.java` with 7 tests covering insert / find /
  list-by-group / cascade-delete-on-group-delete.

## Common variations / gotchas

- **Composite-key tables** (e.g. `deployment_locks` keyed on
  `(component_id, environment_id)`): no auto-generated id; use
  `INSERT … ON CONFLICT DO NOTHING` (Postgres) or catch `SQLException`
  with state starting `23` and treat as held.
- **Bulk replace** (e.g. `acl_rules` — JCasC owns the namespace): wrap
  `DELETE FROM <table>` + batched `INSERT` in one transaction.
- **JSON columns**: store as `String`, deserialize via Jackson in the
  service layer. DAO does not validate JSON.
- **`rs.wasNull()`** after `getLong` / `getInt` / `getBoolean` for
  nullable numeric / boolean columns — the primitive getters return 0
  / false on NULL otherwise.
- **Idempotent insert** (e.g. `version_observations` unique on
  `(component_id, version)`): wrap insert in try/catch, return `-1L`
  on SQLState starting `23`.

## Cross-references

- [dao-test-pattern.md](dao-test-pattern.md) — testing the DAO.
- [schema-migration.md](schema-migration.md) — adding the table first.
- design/07-state-and-storage.md — the schema rationale.
