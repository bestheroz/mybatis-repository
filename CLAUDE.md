# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MyBatis Repository is a Java 8 library published to Maven Central (`io.github.bestheroz:mybatis-repository`). It gives MyBatis mappers generic CRUD methods — no SQL, no XML — by generating statements at runtime from JPA annotations on the entity.

All production code lives in `src/main/java/io/github/bestheroz/mybatis/` — 11 files, ~2,150 lines. There is no sample app and no database in this repo.

## Build and Test

```bash
./gradlew test                      # runs tests; finalizedBy jacocoTestReport, so the report is always produced
./gradlew test --tests "io.github.bestheroz.mybatis.MybatisStringHelperTest"
./gradlew test --tests "io.github.bestheroz.mybatis.MybatisStringHelperTest.escapeSingleQuote_ShouldEscapeCorrectly"
./gradlew spotlessApply             # required before committing
./gradlew spotlessCheck
./gradlew check jar                 # what CI runs for the format gate
./gradlew dependencyUpdates         # ben-manes versions report
```

### Toolchain constraints (important)

CI (`.github/workflows/test.yml`) builds on **JDK 11**. Two things break outside that setup:

- **Spotless needs an old JVM.** It is pinned to `googleJavaFormat("1.7")`, and Spotless 8.x refuses to run it on JVM 17+ (`You are running Spotless on JVM 21. This requires google-java-format of at least 1.17.0`). `compileJava`/`test` are fine on a modern JDK — only the spotless tasks need JDK 11.
- **Gradle must stay on 8.x.** The build applies the `org.springframework.boot` 2.7.18 plugin, which uses Gradle APIs removed in Gradle 9; with a 9.x wrapper even `compileJava` fails at `Failed to notify dependency resolution listener > LenientConfiguration.getFiles()`. 8.14.3 is the last known-good wrapper. (The working tree currently has the wrapper bumped to 9.7.1, which is why local builds fail.)

If a build fails before any of your code is compiled, check the wrapper and JDK first — it is almost certainly one of these two, not the change under review.

## Release

Do not hand-edit `VERSION` in `build.gradle`. Pushing a tag matching `X.Y.Z` triggers `.github/workflows/tag.yml`, which rewrites `VERSION`, runs `./gradlew publishToMavenCentral` with the GPG/Maven Central secrets, and commits the bumped `build.gradle` back to `main`. Every push to any branch also runs `test.yml`, which regenerates and auto-commits the JaCoCo badge under `.github/badges/`.

## Architecture

### The two public interfaces are twins

`MybatisRepository<T>` and `MybatisNoIdRepository<T>` are line-for-line identical (modulo import style) except that the former adds `@Options(useGeneratedKeys = true, keyProperty = "id")` to the two insert provider methods. **Any change to one must be mirrored in the other** — `diff` them before finishing.

Each interface has exactly 7 abstract provider methods (`buildSelectSQL`, `buildSelectOneSQL`, `buildCountSQL`, `buildInsertSQL`, `buildInsertBatchSQL`, `buildUpdateSQL`, `buildDeleteSQL`) annotated with `@SelectProvider`/`@InsertProvider`/`@UpdateProvider`/`@DeleteProvider` pointing at `MybatisCommand`. Everything else — 37 `default` methods such as `getDistinctItemsByMapOrderByLimitOffset` — is a thin wrapper that fills in empty collections and delegates to one of those 7. Adding a convenience API means adding a `default` overload, not new SQL code.

### Runtime entity resolution

Provider methods receive a `ProviderContext`; `MybatisEntityHelper.extractEntityClassFromMapper` walks the mapper's generic interfaces (recursively through parent interfaces) to find the `T` in `MybatisRepository<T>`. This is why a repository must extend `MybatisRepository<Entity>` directly or transitively — there is no other source of entity type information.

### JPA annotations are read by name, not by type

`MybatisEntityHelper` inspects `annotationType().getName()` and matches the strings `jakarta.persistence.Column` / `javax.persistence.Column` (and the `Table` equivalents), invoking `name()` reflectively. That is deliberate: the library ships both `javax` and `jakarta` APIs as `implementation` deps and works under Spring Boot 2.x and 3.x without a compile-time choice. Keep new annotation handling reflective and dual-named.

Consequences to remember:
- **Only fields carrying `@Column` are mapped.** A field without it is invisible to select/insert/update, and passing its name in a condition map throws `MybatisRepositoryException`.
- Table name comes from `@Table(name=...)`, else camelCase→snake_case of the simple class name; column name from `@Column(name=...)`, else camelCase→snake_case of the field name.
- Fields are collected up the class hierarchy (stopping at `Object`), so mapped superclasses work.
- **`@Id` is never read.** The `...ById` methods hardcode the Java field name `"id"` (`Collections.singletonMap("id", id)`), and the interface signatures hardcode `Long`. An entity whose key is named or typed differently cannot use them.
- Results are memoized in `MybatisCommand.FIELD_CACHE` / `TABLE_NAME_CACHE` (static `ConcurrentHashMap`, unmodifiable values). The caches live on `MybatisCommand` but are only populated from `MybatisEntityHelper` — they are never invalidated, so entity metadata is assumed immutable for the JVM's lifetime.

### Condition map protocol

`Map<String, Object>` keys are `fieldName` or `fieldName:conditionType`, split on the first `:` by `MybatisStringHelper`. `Condition` is a strategy enum (`eq`, `ne`, `not`, `in`, `notIn`, `null`, `notNull`, `contains`, `notContains`, `startsWith`, `endsWith`, `lt`, `lte`, `gt`, `gte`); an unknown code silently falls back to `eq`. Adding an operator = adding one enum constant with its `buildClause`, nothing else. `MybatisClauseBuilder` also unwraps a nested `whereConditions` key if the map has one.

`orderByConditions` is a `List<String>` where a `-` prefix means DESC.

### SQL is string-interpolated, not parameter-bound — this is the security boundary

`MybatisClauseBuilder.formatValueForSQL` inlines literals directly into the SQL text (there are no `#{}` placeholders anywhere). Two chokepoints in `MybatisStringHelper` are therefore load-bearing and must not be bypassed:

- `escapeSingleQuote` — escapes `'`, backslash, NUL, newline/CR/tab/backspace/formfeed, `"`, and SUB (`\u001A`) for every value that reaches the SQL string.
- `wrapIdentifier` → `isValidIdentifier` — allowlist regex `^[a-zA-Z][a-zA-Z0-9_]*$`, a large SQL-keyword blocklist, and a length cap, before wrapping in backticks.

Any new value type in `formatValueForSQL`, or any new clause that emits a column name, must route through these. `MybatisRepositoryProperties` caps blast radius (`maxInClauseSize` 1000, `maxStringValueLength` 4000, `maxIdentifierLength` 256); note it is a plain `getInstance()` singleton, **not** bound with `@ConfigurationProperties`, so those limits are only changeable programmatically — there is no `application.yml` key for them today.

Enums implementing `io.github.bestheroz.mybatis.type.ValueEnum` are rendered with `getValue()`; other enums with `name()`.

### Dialect assumption

Backtick identifier quoting, `INSTR` / `RIGHT` / `CHAR_LENGTH` for string conditions, and `LIMIT`/`OFFSET` make the generated SQL MySQL/MariaDB-flavoured. Anything portable across dialects would need a new abstraction, not a tweak.

### Safety rails on writes

`buildUpdateSQL` and `buildDeleteSQL` reject a null/empty `whereConditions` up front, then call `ensureWhereClause`, which re-checks the rendered SQL actually contains `where `. Both guards exist on purpose; keep them.

### Spring Boot registration

`MybatisAutoConfiguration` is registered twice — `META-INF/spring.factories` (Boot 2.x) and `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3.x) — and contains two nested `@ConditionalOnClass` configurations, one per `PostConstruct` namespace. It only logs a readiness message and defines no beans. If you add real configuration, update both registration files.

## Testing

Tests live in `src/test/java/io/github/bestheroz/mybatis/` and must stay in the `io.github.bestheroz.mybatis` package: most helper methods are `protected`, so a test in another package cannot reach them. JUnit 5 + AssertJ, `@DisplayName` in Korean, given/when/then comment structure.

Only `MybatisStringHelperTest` exists today. `MybatisClauseBuilder`, `MybatisEntityHelper`, and `MybatisCommand` are untested — SQL-generation changes are covered by nothing, so verify them by asserting on the generated SQL string.

## Code Style

Google Java Format 1.7 via Spotless with `importOrder()`. Java 8 language level and API only (no `var`, no `List.of`, no `Map.of` in library code). Existing comments and log messages are Korean; match the surrounding file.

`CLAUDE.md`, `.claude/`, and `.omc/` are gitignored in this repo.
