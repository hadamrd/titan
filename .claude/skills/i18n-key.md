---
name: i18n-key
applies-to: pdl-agent
related-design-doc: design/13-i18n-a11y.md (post-v1)
---

## What this is

Add a new i18n key referenced from a Jelly template (`${%key}`). Used
2× this session (folded jerakine Jellies referenced `Name` /
`Description` / `Environment Group` keys we hadn't shipped yet).

## When to use

- A new Jelly template references `${%SomeKey}` — every such reference
  MUST have an entry in `Messages.properties` AND
  `Messages_fr.properties` (`I18nParityTest` enforces).

## NEVER

- **Don't use spaces in property keys.** The localizer generates Java
  method names from keys, and `Environment Group()` is invalid Java.
  Use `EnvironmentGroup` or `Env.Group`.
- **Don't add to only one language.** `I18nParityTest` requires both
  files in sync.
- **Don't introduce a new key when an existing one fits.** Browse the
  existing keys; reuse where possible.

## Procedure

### 1. Choose the key name

Conventions used in this project:
- Top-level form fields: `Name`, `Description`, `EnvironmentGroup`.
- Domain-scoped: `ReleaseFlow.Action.NewRelease`, `ReleaseFlow.Heading.DeployRelease`.
- Empty states: `ReleaseFlow.EmptyState.NoApps`.
- Status labels: `ReleaseFlow.Status.Deployed`.
- Errors: `ReleaseFlow.Error.RootPathRequired`.

If the key would belong to a generic UX area, just `Name` /
`Description` / `EnvironmentGroup` (no prefix). If it's a Release Flow
domain concept, prefix with `ReleaseFlow.`.

### 2. Add to BOTH `.properties` files

```bash
# English
echo 'NewKey=Display text in English' >> src/main/resources/io/adaptiq/titan/Messages.properties

# French
echo 'NewKey=Texte affiché en français' >> src/main/resources/io/adaptiq/titan/Messages_fr.properties
```

### 3. Reference from Jelly

```xml
<f:entry title="${%NewKey}" field="someField">
    <f:textbox/>
</f:entry>
```

OR, when the parent class has a `localize(String)` method:

```xml
<h2>${it.localize('NewKey')}</h2>
```

### 4. Run the parity test

```bash
mvn -q -Dtest='I18nParityTest' test
```

Expected output:
```
Tests run: 3, Failures: 0, Errors: 0
```

If failure — the key is in one file but not the other, or the key has
a space, or the Jelly references it with a typo.

## Worked example

Adding `EnvironmentGroup` (Phase A — fold-in from jerakine):

1. Jelly `EnvironmentChoiceParameterDefinition/config.jelly` had
   `${%Environment Group}` — invalid (space). Changed to `${%EnvironmentGroup}`.
2. `Messages.properties` got `EnvironmentGroup=Environment Group`.
3. `Messages_fr.properties` got `EnvironmentGroup=Groupe d'environnements`.
4. `I18nParityTest` ran → green.

## Common variations / gotchas

- **Existing keys with spaces in the value**: that's fine — the value
  can have spaces; only the KEY must not.
- **Apostrophes in French**: `d'environnements` is fine in
  `.properties` files (no escaping needed).
- **Unicode characters**: the `.properties` files are UTF-8 by
  convention here; characters like `é`, `à`, `—` work directly.
- **Localizer-generated `Messages.java`**: this is auto-generated from
  the `.properties` file. Don't edit it; it'll be regenerated.

## Cross-references

- design/13-i18n-a11y.md (post-v1 — i18n + a11y sweep phase).
- `I18nParityTest.java` — the test that enforces parity.
