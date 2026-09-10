# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MyBatis Repository is a Java 8 library published to Maven Central (`io.github.bestheroz:mybatis-repository`). It gives MyBatis mappers generic CRUD methods — no SQL, no XML — by generating statements at runtime from JPA annotations on the entity.

All production code lives in `src/main/java/io/github/bestheroz/mybatis/` — 12 files, ~2,290 lines. There is no sample app and no database in this repo.

## Build and Test

```bash
./gradlew test                      # runs tests; finalizedBy jacocoTestReport, so the report is always produced
./gradlew test --tests "io.github.bestheroz.mybatis.MybatisStringHelperTest"
./gradlew test --tests "io.github.bestheroz.mybatis.MybatisStringHelperTest.escapeSingleQuote_ShouldEscapeCorrectly"
./gradlew spotlessApply             # required before committing
./gradlew spotlessCheck
./gradlew check jar                 # what CI runs — use this, not `build`
./gradlew dependencyUpdates         # ben-manes versions report
```

**Do not use `./gradlew build`.** The `org.springframework.boot` plugin is applied to what is actually a library, so `bootJar` fails with `Main class name has not been configured and it could not be resolved`. This is long-standing and unrelated to any change you make; CI sidesteps it by running `check jar`.

### Toolchain constraints (important)

The build sits in a narrow JDK window — **JDK 17 is the tested one** (`.github/workflows/*.yml`):

- **JDK 8 cannot compile this project.** `jakarta.annotation-api:3.0.0` is Java 11 bytecode (major 55) and `MybatisAutoConfiguration` references `@jakarta.annotation.PostConstruct` directly, so javac 8 fails with `class file has wrong version 55.0, should be 52.0`. Building on 8 is not an option, regardless of the Java 8 *target*.
- **JDK 11 cannot configure this project.** `com.vanniktech.maven.publish:0.37.0` requires JVM 17+; the build fails at configuration time with `Dependency requires at least JVM runtime version 17`.
- **Gradle must stay on 8.x.** The `org.springframework.boot` 2.7.18 plugin uses Gradle APIs removed in Gradle 9; with a 9.x wrapper even `compileJava` fails at `Failed to notify dependency resolution listener > LenientConfiguration.getFiles()`. 8.14.3 is known-good.
- **Google Java Format is capped by the build JVM**, and Spotless reports the mismatch as a confusing `Cannot fingerprint input property 'stepsInternalEquality' ... cannot be serialized` failure rather than a plain message. 1.28.0 needs JVM 17+, 1.30.0 needs JVM 21+, so the version is pinned to 1.28.0 to match CI's JDK 17. Leaving `googleJavaFormat()` unpinned makes Spotless pick the newest version the *current* JVM allows, so a developer on JDK 21 and CI on JDK 17 would reformat each other's code forever. Raise the pin only together with the CI JDK.

If a build fails before any of your code is compiled, check the wrapper and JDK first — it is almost certainly one of these, not the change under review.

### Java 8 is a deliberate, standing decision — do not "modernize" it

Targeting Java 8 was reviewed and kept on purpose, to keep the library usable by Spring Boot 2.x / `javax.persistence` consumers still on a Java 8 JVM. It is not neglect, and raising it is not an improvement to offer unprompted.

The review that settled it: bumping to 11 buys only `Set.of`/`List.of`/`var` here (~10 lines, no behaviour change) while cutting off Java 8 users; 17 additionally buys `instanceof` pattern matching, which would tidy the 12-branch cast chain in `MybatisClauseBuilder.formatValueForSQL`, but a 17 target effectively means "Spring Boot 3 only" — which would make the whole `javax`/`jakarta` dual-namespace machinery pointless. Raising the target also does **not** improve runtime performance; that is decided by the consumer's JVM, not by the bytecode level. If the target is ever raised, skip 11, go straight to 17, and pair it with dropping `javax` support as a 1.0 breaking release.

The larger wins are version-independent and still open: the 838 duplicated lines across the twin repository interfaces (37% of the source), the string-interpolated SQL, and the untested SQL-generation classes.

### Java 8 compatibility is enforced by `--release`, not by `targetCompatibility`

`sourceCompatibility`/`targetCompatibility` alone emit `-source 8 -target 8`, which restricts *language syntax* but still links against the build JDK's class library — `String#isBlank()` and `List.of()` compile silently and produce major-52 bytecode that throws `NoSuchMethodError` on a real Java 8 JVM. The build therefore sets:

```groovy
tasks.withType(JavaCompile).configureEach { options.release.set(8) }
```

Do not remove it, and do not "fix" a `cannot find symbol` on a Java 9+ API by dropping it — that error is the guard working. Verify the output stays at `major version: 52`.

## Dependencies are `compileOnly`

Every framework dependency (spring-boot-starter, mybatis-spring-boot-starter, both `javax.*` and `jakarta.*` API jars) is `compileOnly`, so the published POM carries **zero runtime dependencies**. That is deliberate: `jakarta.persistence-api:3.2.0` and `mybatis-spring-boot-starter:3.0.5` are Java 17 bytecode (major 61), and shipping them as runtime deps forced Java-17-only jars onto Java 8 consumers — while also pushing both the `javax` and `jakarta` API jars onto everyone, which contradicts the reflective dual-namespace design. Consumers are Spring Boot apps that already have these.

Two consequences: `compileOnly` does not reach the test classpath, so anything a test needs must be repeated as `testImplementation` (the `dependencies` block already mirrors all six); and adding a new `implementation` dependency silently re-introduces the problem — check `build/publications/maven/pom-default.xml` for `<scope>runtime</scope>` after any dependency change.

## Release

Do not hand-edit `VERSION` in `build.gradle`. Pushing a tag matching `X.Y.Z` triggers `.github/workflows/tag.yml`, which rewrites `VERSION`, runs `./gradlew publishToMavenCentral` with the GPG/Maven Central secrets, and commits the bumped `build.gradle` back to `main`. Every push to any branch also runs `test.yml`, which regenerates and auto-commits the JaCoCo badge under `.github/badges/`.

That badge job pushes back to the branch it ran on, so two pushes seconds apart used to leave the older run committing onto a parent that is no longer the tip — it failed with `refs/heads/main:refs/heads/main [rejected] (non-fast-forward)` and the badge silently went unrefreshed. Three guards now cover it and all three are load-bearing: `concurrency` with `cancel-in-progress` drops the superseded run, `pull: '--rebase --autostash'` replays the badge commit onto whatever landed meanwhile (`tag.yml`'s version bump is the one that races legitimately), and `push_attempts: '3'` re-pulls between attempts to close the gap between that pull and the push. The badge push has no other retry path.

The `publishToMavenCentral()` call inside the `mavenPublishing` block is what registers the Central repository and creates that task. It was once deleted during a plugin upgrade, which silently removed the task and would have failed the next release with `Task 'publishToMavenCentral' not found`. If you touch the publishing block, confirm the task still exists:

```bash
./gradlew help --task publishToMavenCentral
```

## Architecture

### The two public interfaces are twins

`MybatisRepository<T>` and `MybatisNoIdRepository<T>` are line-for-line identical (modulo import style) except that the former adds `@Options(useGeneratedKeys = true, keyProperty = "id")` to the two insert provider methods. **Any change to one must be mirrored in the other** — `diff` them before finishing.

Each interface has exactly 7 abstract provider methods (`buildSelectSQL`, `buildSelectOneSQL`, `buildCountSQL`, `buildInsertSQL`, `buildInsertBatchSQL`, `buildUpdateSQL`, `buildDeleteSQL`) annotated with `@SelectProvider`/`@InsertProvider`/`@UpdateProvider`/`@DeleteProvider` pointing at `MybatisCommand`. Everything else — 37 `default` methods such as `getDistinctItemsByMapOrderByLimitOffset` — is a thin wrapper that fills in empty collections and delegates to one of those 7. Adding a convenience API means adding a `default` overload, not new SQL code.

### Runtime entity resolution

Provider methods receive a `ProviderContext`; `MybatisEntityHelper.extractEntityClassFromMapper` walks the mapper's generic interfaces (recursively through parent interfaces) to find the `T` in `MybatisRepository<T>`. This is why a repository must extend `MybatisRepository<Entity>` directly or transitively — there is no other source of entity type information.

Consumers reach for that method from their own MyBatis interceptors, so `MybatisEntityHelper` has a no-arg constructor: the method never touches `stringHelper`, and making callers hand-build a `MybatisStringHelper` only to get at it exposed the SQL-escaping chokepoint for no reason.

### JPA annotations are read by name, not by type

`MybatisEntityHelper` inspects `annotationType().getName()` and matches the strings `jakarta.persistence.Column` / `javax.persistence.Column` (and the `Table` equivalents), invoking `name()` reflectively. That is deliberate: the library compiles against both `javax` and `jakarta` APIs (as `compileOnly`) and works under Spring Boot 2.x and 3.x without a compile-time choice. Keep new annotation handling reflective and dual-named.

Consequences to remember:
- **Only fields carrying `@Column` are mapped.** A field without it is invisible to select/insert/update, and passing its name in a condition map throws `MybatisRepositoryException`.
- Table name comes from `@Table(name=...)`, else camelCase→snake_case of the simple class name; column name from `@Column(name=...)`, else camelCase→snake_case of the field name.
- Fields are collected up the class hierarchy (stopping at `Object`), so mapped superclasses work.
- **`@Id` is never read.** The `...ById` methods hardcode the Java field name `"id"` (`Collections.singletonMap("id", id)`), and the interface signatures hardcode `Long`. An entity whose key is named or typed differently cannot use them.
- Results are memoized in seven static `ConcurrentHashMap`s on `MybatisCommand`: `FIELD_CACHE`, `TABLE_NAME_CACHE`, `FIELD_NAME_CACHE` (the `@Column` field-name set), `COLUMN_NAME_CACHE` (nested, `entityClass → fieldName → column`), `WRAPPED_COLUMN_CACHE` (nested, `entityClass → fieldName →` the backtick-wrapped column — so every clause that emits a column name pays `getColumnName` + `wrapIdentifier` once per field, not once per query), `ORDERED_FIELD_CACHE` (`entityClass → List<Field>` in `FIELD_NAME_CACHE` iteration order) and `MAPPER_ENTITY_CACHE` (mapper interface → entity class). They live on `MybatisCommand` but are only populated from `MybatisEntityHelper` — they are never invalidated, so entity metadata is assumed immutable for the JVM's lifetime. Values are unmodifiable, so a caller must not mutate what `getEntityFields` returns.
- **Only successful lookups are cached.** `getColumnName`, `getWrappedColumnName` and `extractEntityClassFromMapper` deliberately leave their failure paths uncached, so an unmapped field name keeps throwing `MybatisRepositoryException` on every call instead of being remembered as a miss. A side effect of `WRAPPED_COLUMN_CACHE` is that `isValidIdentifier`'s length cap and keyword blocklist now run once per `(entity, field)` rather than once per query — fine because entity metadata is immutable for the JVM's lifetime and `maxIdentifierLength` is programmatic-only, but it means changing that limit after the first query does not re-validate already-cached names.
- `getAllNonExcludedFields` calls `setAccessible(true)` once while populating `FIELD_CACHE`. `toMap` therefore reads fields without locking; do not reintroduce a `synchronized (field)` there — the `Field` objects are shared by every thread through the cache, so locking them serialises batch inserts across the whole JVM.
- **Batch insert no longer goes through `toMap`.** It reads values straight off `ORDERED_FIELD_CACHE`'s `List<Field>`, which is built in `FIELD_NAME_CACHE` iteration order — the same order `INTO_COLUMNS` is emitted in, so columns and values stay aligned. That list resolves duplicate field names the way `toMap`'s `put` did (the last `Field` up the hierarchy wins), and a field whose `get` throws yields `null`, matching the old "absent key → `map.get` returns null" path. `MybatisSqlGenerationTest.buildInsertBatchSQL_ShouldKeepColumnOrderStable` pins the whole generated statement, because a single-slot drift between the two orders would silently write values into the wrong columns. Its `TwelveColumn` rows must carry **distinct** values whose suffixes match the column names (`c07` → `'a07'`) — the test was first written with all-`null` fields, and an all-`null` row renders identically no matter how the order is scrambled, so it caught nothing. Any test that claims to guard this alignment has to encode the column-value pairing in the pinned string.
- **`toMap` must build a plain `new HashMap<>()`.** `buildInsertSQL` iterates that map's `entrySet()`, so its bucket order *is* the INSERT column order — and so is the UPDATE `SET` order, because `updateById`/`updateByMap(entity, …)` pass `MybatisCommand.toMap(entity)` straight in as the `updateMap`. Pre-sizing it (`new HashMap<>(fields.size() / 0.75f + 1)`) picks a different table capacity than the default-16-then-double path, which silently reorders the emitted columns — confirmed for entities with 3, 4, 5, 12 and 24 `@Column` fields. The SQL stays semantically correct (`SQL.VALUES` keeps each column paired with its value), so nothing fails; only the text changes. `MybatisSqlGenerationTest.buildInsertSQL_ShouldKeepColumnOrderStable` pins the generated string for 4- and 12-column entities to catch exactly this; it is the only guard, since `buildUpdateSQL` cannot be called from a test (`ProviderContext` has a package-private constructor). The same hazard is why `getEntityFields` caches the very `Collectors.toSet()` result the old code produced instead of switching to a `LinkedHashSet`.

### Batch insert rows are separated with `ADD_ROW()`, not with parentheses of our own

MyBatis' `AbstractSQL.insertSQL` renders each entry of its `valuesList` wrapped in one pair of parentheses (`sqlClause(builder, "VALUES", list, "(", ")", ", ")`). So handing `INTO_VALUES` a single pre-joined string like `(1,'a'), (2,'b')` produces `VALUES ((1,'a'), (2,'b'))` — MySQL reads the inner parens as row values and rejects it (`Operand should contain 1 column(s)`; sqlite says `row value misused`). The library shipped that shape from its first commit, so `insertBatch` never produced runnable SQL.

`buildInsertBatchSQL` therefore appends one row per `INTO_VALUES` call with `ADD_ROW()` between rows and lets MyBatis add the parentheses; the row separator in the rendered text is `\n, `. A single reused `StringBuilder` (`row.setLength(0)` per row) keeps the per-row string count at one. Do not go back to building the whole `VALUES` body yourself, and do not add parentheses around a row.

`ADD_ROW()` was added in MyBatis **3.5.2**, so that is the floor for `insertBatch` (verified with `javap`: absent in 3.5.1, present in 3.5.2). MyBatis is `compileOnly`, so a consumer pinned to `mybatis-spring-boot-starter` 2.0.0/2.0.1 would get a `NoSuchMethodError` — but that same consumer was previously getting SQL the database refused, so nobody can have been depending on it.

### Condition map protocol

`Map<String, Object>` keys are `fieldName` or `fieldName:conditionType`, split on the first `:` by `MybatisStringHelper`. `Condition` is a strategy enum (`eq`, `ne`, `not`, `in`, `notIn`, `null`, `notNull`, `contains`, `notContains`, `startsWith`, `endsWith`, `lt`, `lte`, `gt`, `gte`); an unknown code silently falls back to `eq`. Adding an operator = adding one enum constant with its `buildClause`, nothing else. `MybatisClauseBuilder` also unwraps a nested `whereConditions` key if the map has one.

`orderByConditions` is a `List<String>` where a `-` prefix means DESC.

### SQL is string-interpolated, not parameter-bound — this is the security boundary

`MybatisClauseBuilder.formatValueForSQL` inlines literals directly into the SQL text (there are no `#{}` placeholders anywhere). Two chokepoints in `MybatisStringHelper` are therefore load-bearing and must not be bypassed:

- `escapeSingleQuote` — escapes `'`, backslash, NUL, newline/CR/tab/backspace/formfeed, `"`, and SUB (`\u001A`) for every value that reaches the SQL string.
- `wrapIdentifier` → `isValidIdentifier` — allowlist regex `^[a-zA-Z][a-zA-Z0-9_]*$`, a large SQL-keyword blocklist, and a length cap, before wrapping in backticks.

Any new value type in `formatValueForSQL`, or any new clause that emits a column name, must route through these. `MybatisRepositoryProperties` caps blast radius (`maxInClauseSize` 1000, `maxStringValueLength` 1 MiB, `maxIdentifierLength` 256) and also holds `zoneId`, the wall clock used for datetime literals (default UTC). It is a plain `getInstance()` singleton, **not** bound with `@ConfigurationProperties`; the size limits remain programmatic-only, while `zoneId` is the one value read from `application.yml` (`mybatis-repository.timezone`, declared in `META-INF/additional-spring-configuration-metadata.json` for IDE completion) — see the registration note below for why that happens in an `ApplicationContextInitializer` and not in a bean.

`zoneId` is `null` when unset, and that null is load-bearing: `getZoneId()` (used by `Instant`, `OffsetDateTime`, ISO-8601 strings) falls back to UTC, while `getDateZoneId()` (used by the `java.util.Date` branch) falls back to `ZoneId.systemDefault()`. Those are the two different defaults those paths shipped with before the setting existed, so a consumer who configures nothing gets byte-identical SQL, and one who configures a zone gets a single wall clock for every type. Collapsing the two getters into one would silently move stored values for somebody. The string setter is named `setTimezone`, not an overload of `setZoneId`, because a same-named overload makes `setZoneId(null)` ambiguous and gives JavaBean binding two candidates.

The zone-carrying branches of `formatValueForSQL` all funnel through `MybatisStringHelper.instantToString`, so `Instant`, `OffsetDateTime` and `ZonedDateTime` land on one wall clock; `LocalDateTime`/`LocalDate` are already wall clocks and are deliberately not shifted. Four branch-ordering facts are load-bearing:

- **`java.sql.Date` and `java.sql.Time` must be tested before `java.util.Date`.** They are subclasses, but their `toInstant()` throws `UnsupportedOperationException`, so the `Date` branch blows up on them. They are rendered through `toLocalDate()` / `toLocalTime()`, which decode the epoch millis with **`ZoneId.systemDefault()`, deliberately ignoring `mybatis-repository.timezone`** — these are JDBC's local-field types, and a value produced by the driver or by `Date.valueOf(localDate)` is already normalized to the JVM zone, so re-decoding it through a different zone moves the date by a day. The cost is that a millis-constructed `new java.sql.Date(System.currentTimeMillis())` follows the JVM zone rather than the configured one; that trade was taken on purpose and `MybatisClauseBuilderTest` pins it by forcing `TimeZone.setDefault("UTC")` against a configured `Asia/Seoul`. `java.sql.Timestamp` is a real instant and stays on the `Date` branch, where it does follow `getDateZoneId()`.
- **`ZonedDateTime`, `OffsetTime` and `LocalTime` each need their own branch.** Without them they fall through to the generic `toString()` tail and `'2025-01-02T12:34:56+09:00[Asia/Seoul]'`, `'12:34:56+09:00'`, or a seconds-eliding `'12:34'` go into the SQL text. `ZonedDateTime` follows the configured zone like `OffsetDateTime`; the two time-only types have no date to anchor a conversion, so they drop the offset and keep the wall clock, matching `java.sql.Time`.
- **`Number` and `Boolean` are tested second, right after `String`.** They used to sit eleventh, so every `Long` id walked the whole temporal chain first. Nothing below them can be a `Number` or a `Boolean` — the temporal types, `Enum`, `Collection` and `Map` all exclude them — so the move changes no output for any type that is not deliberately pathological (a class extending `Number` *and* implementing `Collection`).
- **ISO-8601 strings are parsed by `MybatisStringHelper.parseIso8601`, not `Instant.parse`.** `Instant.parse` only started accepting `+09:00` in JDK 12, so on a real Java 8 consumer the same string throws. `parseIso8601` tries `ISO_OFFSET_DATE_TIME` (Java 8, accepts `Z` and `±HH:MM`), then a builder-made formatter for the colon-less `±HHMM` form that `isISO8601String` also lets through, then `Instant.parse` last — that final step is not redundant, it is the only one that accepts a leap second (`23:59:60Z`), which the other two reject under `ResolverStyle.STRICT`. When all three fail it returns `null` rather than throwing. `isISO8601String` is a cheap pre-filter that accepts 2 or 3 hyphens (a negative offset adds one) and is allowed to be loose precisely because a failed parse falls back to plain string escaping.

Enums implementing `io.github.bestheroz.mybatis.type.ValueEnum` are rendered with `getValue()`; other enums with `name()`.

### Table names are never identifier-wrapped; column names always are

All six build methods emit the table name raw. `buildInsertBatchSQL` used to be the exception — it ran the table name through `wrapIdentifier`, which applies the *column* rules: the SQL-keyword blocklist and an allowlist regex with no `.`. So `@Table(name = "shop.orders")` and `@Table(name = "order")` threw `IllegalArgumentException` on batch insert while every other statement built fine. Do not reintroduce that asymmetry by wrapping one call site; a real fix needs a table-specific rule applied to all six at once.

Column names are the opposite and are still uneven: SELECT, ORDER BY and INSERT go through `getWrappedColumnName` → `wrapIdentifier` → `isValidIdentifier`, while WHERE and UPDATE SET build the backticks inline in `Condition` and `buildEqualClause`, skipping validation. That is not an injection hole today — a condition-map key must resolve to a real `@Column` field or `getColumnName` throws — but it means the same `@Column(name = "left")` is rejected in a SELECT and accepted in a WHERE, and the defense rests on the field-existence check rather than on the allowlist.

### `maxStringValueLength` is a runaway guard, not a column-width check

It applies to every value that becomes a quoted literal, `String` included. It did not always: the check lived only in the final "everything else → `toString()`" branch of `formatValueForSQL`, so a 5000-character `String` sailed through while the same 5000 characters in a `StringBuilder` threw. Both now go through `ensureValueLength`.

The default is 1 MiB rather than the old 4000 precisely because the old number was never enforced on the common type — turning it on at 4000 would have started rejecting long `TEXT` values that worked in every previous release. Do not lower it back to a column-width-sized number; a consumer who wants that can set it.

### Dialect assumption

Backtick identifier quoting, `INSTR` / `RIGHT` / `CHAR_LENGTH` for string conditions, and `LIMIT`/`OFFSET` make the generated SQL MySQL/MariaDB-flavoured. Anything portable across dialects would need a new abstraction, not a tweak.

### Debug logging must stay behind `isDebugEnabled`

Every build method renders its SQL exactly once and passes it through `MybatisCommand.renderAndLog`. The obvious-looking `log.debug("insert SQL: {}", sql.toString().replaceAll("\n", " "))` is a trap: SLF4J defers *formatting*, not argument evaluation, so that line rendered the statement and built a regex-replaced copy of it on every query even with debug off. On a 1000-row batch insert that is a wasted 62 KB copy and a `Pattern` compile per statement — measured at ~110 µs, about an eighth of the whole call. Keep the `isDebugEnabled` guard, keep the single render, and use `replace('\n', ' ')` (char) rather than `replaceAll` (regex).

### Safety rails on writes

`buildUpdateSQL` and `buildDeleteSQL` reject a null/empty `whereConditions` up front, then call `ensureWhereClause` with the number of conditions `buildWhereClause` actually appended. Both guards exist on purpose; keep them, and keep the second one counting rather than reading the rendered text.

`ensureWhereClause` used to scan the finished statement for the substring `where `, which the SET clause could satisfy: `UPDATE t SET \`memo\` = 'delivered somewhere else'` passed the guard with no WHERE clause at all and updated every row. The front-door check does not catch that case either — `extractWhereConditions` unwraps a nested `whereConditions` key, so `{"whereConditions": {}}` is a size-1 map that survives `isEmpty()` and then contributes zero conditions. Counting is exact: MyBatis renders `WHERE` only when the list is non-empty, so `count > 0` is precisely "a WHERE clause was emitted".

### Spring Boot registration

`MybatisAutoConfiguration` is registered twice — `META-INF/spring.factories` (Boot 2.x) and `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3.x) — and contains two nested `@ConditionalOnClass` configurations, one per `PostConstruct` namespace. It defines no beans; the two nested configurations only log a readiness message, which now carries the resolved zone so the value is visible at boot. If you add real configuration, update both registration files.

`mybatis-repository.timezone` is applied by `MybatisTimezoneInitializer`, an `ApplicationContextInitializer` registered in `spring.factories`. Two things about that choice are load-bearing.

**Do not move it into a bean constructor.** Auto-configuration is registered through a `DeferredImportSelector`, so its beans are created after every user bean; a consumer that queries from a constructor, `@PostConstruct`, or `InitializingBean` would write those rows with the default zone while the rest of the app writes the configured one — silently, with no error. An initializer runs before refresh, which closes that window.

**Do not switch it to `EnvironmentPostProcessor`,** which is the more obvious hook. Boot 4 moved that interface from `org.springframework.boot.env` to `org.springframework.boot`, so whichever package you compile and register against, the other major silently ignores the registration — verified against 4.2.0-M1, whose jar has no `boot.env.EnvironmentPostProcessor` at all. `ApplicationContextInitializer` is a Spring Framework interface and keeps its name across Boot 2, 3 and 4. It returns `LOWEST_PRECEDENCE` so any initializer that contributes property sources has already run.

The initializer only covers contexts built by `SpringApplication`, because that is what loads `spring.factories` initializers — so `MybatisAutoConfiguration` also implements `EnvironmentAware` and applies the same value. That is not redundancy to trim: it restores a hand-built `AnnotationConfigApplicationContext` (classic non-Boot Spring, or a WAR that bypasses `SpringBootServletInitializer`), where the initializer never runs. Applying twice is safe precisely because unset is `null` — both paths compute the same value. Two consequences worth knowing: `ApplicationContextRunner` loads neither path's registration and needs `withInitializer(...)` explicitly (`@SpringBootTest` is fine, it builds a real `SpringApplication`), and `spring.autoconfigure.exclude` no longer switches the timezone off, since the initializer is not part of the auto-configuration.

Note that `zoneId` lands in a process-wide singleton, so two Spring contexts in one JVM (tests, multi-tenant setups) share the last value written.

## Testing

Tests live in `src/test/java/io/github/bestheroz/mybatis/` and must stay in the `io.github.bestheroz.mybatis` package: most helper methods are `protected`, so a test in another package cannot reach them. JUnit 5 + AssertJ, `@DisplayName` in Korean, given/when/then comment structure.

`MybatisStringHelperTest`, `MybatisClauseBuilderTest` (datetime literal zone handling only — every temporal branch plus the ISO-8601 string forms), `MybatisEntityHelperTest`, `MybatisSqlGenerationTest`, `MybatisTimezoneInitializerTest`, and `MybatisTimezoneBootstrapTest` (boots a real `SpringApplicationBuilder` context to prove the zone is applied before user beans initialize) exist today.

`MybatisSqlGenerationTest` covers the SQL-generation path by asserting on the generated string — insert, batch insert (whole statement pinned, plus the null-element guard), IN clauses, every `Condition` branch, `appendSelectColumns` and `appendOrderBy`, `ensureWhereClause`, and column-name resolution including the cached and error paths. It cannot reach `buildSelectSQL`/`buildUpdateSQL`/`buildDeleteSQL`/`buildCountSQL` directly, because `ProviderContext` has a package-private constructor and cannot be built from a test; exercise those through `MybatisClauseBuilder` instead. `MybatisStringHelperTest` pins `escapeSingleQuote` against the pre-refactor chained-`replace` implementation over a generated corpus — that oracle is the guard on the escaping chokepoint, so keep it if the implementation changes again. Tests that touch `MybatisRepositoryProperties.getInstance()` must reset it in `@AfterEach`; it is a process-wide singleton and leaks across test classes otherwise.

## Code Style

Spotless 8.10.2 runs `importOrder()`, `cleanthat()`, `googleJavaFormat('1.28.0')` and `formatAnnotations()`. CleanThat is pinned to `sourceCompatibility('1.8')` so it never rewrites code into Java 9+ constructs. Java 8 language level and API only (no `var`, no `List.of`, no `Map.of` in library code) — `options.release = 8` enforces this at compile time. Existing comments and log messages are Korean; match the surrounding file.
