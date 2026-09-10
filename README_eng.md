# MyBatis Repository

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bestheroz/mybatis-repository)](https://search.maven.org/artifact/io.github.bestheroz/mybatis-repository)
![GitHub top language](https://img.shields.io/github/languages/top/bestheroz/mybatis-repository)
![Coverage](.github/badges/jacoco.svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/bestheroz/mybatis-repository/blob/main/LICENSE)

[한국어](README.md)

A Java library that gives MyBatis mappers a full set of CRUD methods. You write no SQL and no XML — statements are generated at runtime from the JPA annotations already on your entity.

```java
@Mapper
public interface UserRepository extends MybatisRepository<User> {}
```

That single line yields 37 methods covering queries, filtering, sorting, paging, batch insert, update and delete. There is nothing to implement.

## Requirements

|             |                                                            |
| ----------- | ---------------------------------------------------------- |
| Java        | 8 or later                                                  |
| Spring Boot | 2.x (`javax.persistence`) or 3.x (`jakarta.persistence`)     |
| MyBatis     | `mybatis-spring-boot-starter`                               |
| Database    | MySQL · MariaDB                                             |
| Kotlin      | 1.x or later (optional)                                     |

> **Database constraint**: the generated SQL uses backtick quoting plus `INSTR` / `RIGHT` / `CHAR_LENGTH` / `LIMIT ... OFFSET`. It will not run on PostgreSQL or Oracle.

This library has **no runtime dependencies.** It uses whichever Spring Boot, MyBatis and JPA API your project already provides, so `javax` and `jakarta` both work with no extra configuration.

## Installation

```groovy
dependencies {
    implementation 'io.github.bestheroz:mybatis-repository:0.9.0'
}
```

```xml
<dependency>
    <groupId>io.github.bestheroz</groupId>
    <artifactId>mybatis-repository</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Getting started

**1. Entity** — only fields annotated with `@Column` are mapped.

```java
@Data
@Table(name = "users")
public class User {
    @Column private Long id;
    @Column private String loginId;
    @Column private String name;
    @Column private Boolean removedFlag;
    @Column private Instant createdAt;
}
```

Without `@Table` the class name is used, and without `@Column(name = ...)` the field name is used — both converted to snake_case (`removedFlag` → `removed_flag`).

**2. Repository** — declare the interface, nothing else.

```java
@Mapper
@Repository
public interface UserRepository extends MybatisRepository<User> {}
```

For a table without an auto-increment primary key, extend `MybatisNoIdRepository<User>` instead. It exposes the same methods; only `@Options(useGeneratedKeys = true)` is omitted.

**3. Use it**

```java
List<User> users = userRepository.getItemsByMapOrderByLimitOffset(
        Map.of("removedFlag", false, "name:contains", "kim"),
        List.of("name", "-createdAt"),
        10, 0);
```

```sql
SELECT `id`, `login_id`, `name`, `removed_flag`, `created_at` FROM users
 WHERE (`removed_flag` = false AND INSTR(`name`, 'kim') > 0)
 ORDER BY `name` ASC, `created_at` DESC
 LIMIT 10 OFFSET 0
```

## Query methods — the name is the signature

The 25 query methods are combinations of the segments below. Append only the segments you need.

```
get ⟨Distinct|Target⟩ Items ⟨ByMap⟩ ⟨OrderBy⟩ ⟨LimitOffset⟩
```

| Segment       | Parameter                     | When omitted   |
| ------------- | ----------------------------- | -------------- |
| `Distinct`    | `Set<String>` of field names   | all columns    |
| `Target`      | `Set<String>` of field names   | all columns    |
| `ByMap`       | `Map<String, Object>` filter   | no filter      |
| `OrderBy`     | `List<String>` sort            | no sorting     |
| `LimitOffset` | `Integer limit, offset`        | all rows       |

```java
userRepository.getItems();                                    // everything
userRepository.getItemsByMap(where);                          // filtered
userRepository.getItemsOrderByLimitOffset(order, 10, 0);      // sorted + paged
userRepository.getDistinctItemsByMap(Set.of("name"), where);  // DISTINCT
userRepository.getTargetItems(Set.of("id", "name"));          // selected columns
```

Arguments are passed in the order the segments appear in the name. `Distinct` and `Target` can only be combined through the single method `getDistinctAndTargetItemsByMapOrderByLimitOffset`.

### Sorting

Pass field names in a `List<String>`; prefix with `-` for descending order.

```java
List.of("name", "-createdAt")   // ORDER BY `name` ASC, `created_at` DESC
```

## Single row · count · writes

| Method                       | Description                        |
| ---------------------------- | ---------------------------------- |
| `getItemById(Long)`          | `Optional<T>`                      |
| `getItemByMap(Map)`          | `Optional<T>`                      |
| `countAll()`                 | total row count                    |
| `countByMap(Map)`            | matching row count                 |
| `insert(T)`                  | insert one row                     |
| `insertBatch(List<T>)`       | insert many rows in one statement  |
| `updateById(T, Long)`        | update every field of the entity   |
| `updateByMap(T, Map)`        | same, matched by filter            |
| `updateMapById(Map, Long)`   | update only the listed fields      |
| `updateMapByMap(Map, Map)`   | same, matched by filter            |
| `deleteById(Long)`           | delete one row                     |
| `deleteByMap(Map)`           | delete by filter                   |

## Conditions (WHERE)

Keys are `fieldName` or `fieldName:conditionType`. Omitting the condition type means `eq`.

```java
Map.of(
    "removedFlag", false,        // `removed_flag` = false
    "name:contains", "kim",      // INSTR(`name`, 'kim') > 0
    "id:in", Set.of(1L, 2L),     // `id` IN (1, 2)
    "deletedAt:null", null       // `deleted_at` IS NULL
);
```

| Condition type              | Generated SQL                          |
| --------------------------- | -------------------------------------- |
| omitted · `eq`              | `` `col` = v ``                        |
| `ne` · `not`                | `` `col` <> v ``                       |
| `in`                        | `` `col` IN (…) ``                     |
| `notIn`                     | `` `col` NOT IN (…) ``                 |
| `null`                      | `` `col` IS NULL ``                    |
| `notNull`                   | `` `col` IS NOT NULL ``                |
| `contains`                  | `` INSTR(`col`, v) > 0 ``              |
| `notContains`               | `` INSTR(`col`, v) = 0 ``              |
| `startsWith`                | `` INSTR(`col`, v) = 1 ``              |
| `endsWith`                  | `` RIGHT(`col`, CHAR_LENGTH(v)) = v `` |
| `lt` · `lte` · `gt` · `gte` | `` `col` < <= > >= v ``                |

`in` and `notIn` accept **`Set` only** — passing a `List` throws — and are capped at 1,000 entries by default.

## Configuration

Datetime values are inlined into the SQL text as literals, not bound as parameters. The zone you pick is therefore the wall clock that ends up stored in the database.

```yaml
mybatis-repository:
  timezone: Asia/Seoul   # defaults to UTC when omitted
```

Set it and `Instant`, `OffsetDateTime`, `ZonedDateTime`, ISO8601 strings and `Date`/`Timestamp` all share one wall clock. Leave it unset and each type keeps the default it has always had — `UTC` for the `Instant` family, the JVM default zone for `Date`/`Timestamp`.

Some types deliberately do not follow the setting. `LocalDateTime`/`LocalDate`/`LocalTime` are wall-clock values already, and `OffsetTime` has no date to anchor a conversion, so its offset is dropped. JDBC's `java.sql.Date`/`java.sql.Time` are read with the JVM default zone, because the driver or `valueOf` already normalized them to it — following the setting there would shift the date by a day.

> `@SpringBootTest` picks this up, but `ApplicationContextRunner` does not load `spring.factories` initializers, so the setting is ignored there. Register `withInitializer(new MybatisTimezoneInitializer())` explicitly in runner-style tests.

> JDBC reads timestamps back with `ResultSet#getTimestamp`, which uses the JVM default time zone. If this setting differs from that zone, **stored and retrieved instants drift apart by exactly that offset.** An unknown zone ID fails startup rather than silently falling back to the default.

## Things worth knowing

- **A field without `@Column` does not exist** as far as this library is concerned. It is skipped by select, insert and update, and naming it in a condition map throws `MybatisRepositoryException`.
- **The `...ById` methods assume a `Long` field named `id`.** The `@Id` annotation is never read. If your primary key has a different name or type, use the `...ByMap` variants.
- **UPDATE and DELETE throw when the filter is empty**, so an accidental update-all or delete-all is not possible.
- **An unrecognised condition type silently falls back to `eq`.** A typo such as `name:contain` becomes an equality comparison rather than an error.
- Map keys are **Java field names in camelCase**, not column names.
- Queries list the `@Column` fields explicitly rather than using `SELECT *`. Column order is not guaranteed.
- Values are escaped and inlined as SQL literals; identifiers are validated against a character allowlist and a SQL keyword blocklist.

## Contributing

Issues and pull requests are welcome. Run `./gradlew spotlessApply` before submitting code changes.

## License

[Apache License 2.0](LICENSE)
