# MyBatis Repository

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bestheroz/mybatis-repository)](https://search.maven.org/artifact/io.github.bestheroz/mybatis-repository)
![GitHub top language](https://img.shields.io/github/languages/top/bestheroz/mybatis-repository)
![Coverage](.github/badges/jacoco.svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/bestheroz/mybatis-repository/blob/main/LICENSE)

[English](README_eng.md)

MyBatis 매퍼에 CRUD 메서드를 붙여주는 자바 라이브러리입니다. SQL 도 XML 도 작성하지 않고, 엔티티에 붙은 JPA 애노테이션만 읽어 런타임에 SQL 을 생성합니다.

```java
@Mapper
public interface UserRepository extends MybatisRepository<User> {}
```

이 한 줄로 조회·필터·정렬·페이징·배치삽입·수정·삭제까지 37개 메서드가 생깁니다. 구현할 것은 없습니다.

## 요구 사항

|             |                                                          |
| ----------- | -------------------------------------------------------- |
| Java        | 8 이상                                                     |
| Spring Boot | 2.x (`javax.persistence`) 또는 3.x (`jakarta.persistence`)  |
| MyBatis     | `mybatis-spring-boot-starter`                             |
| DBMS        | MySQL · MariaDB                                           |
| Kotlin      | 1.x 이상 (선택)                                             |

> **DBMS 제약**: 생성되는 SQL 이 백틱 인용과 `INSTR` / `RIGHT` / `CHAR_LENGTH` / `LIMIT ... OFFSET` 을 사용합니다. PostgreSQL·Oracle 에서는 동작하지 않습니다.

이 라이브러리는 **런타임 의존성이 없습니다.** Spring Boot, MyBatis, JPA API 는 여러분 프로젝트에 이미 있는 것을 그대로 씁니다. `javax` 를 쓰든 `jakarta` 를 쓰든 추가 설정이 필요 없습니다.

## 설치

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

## 시작하기

**1. 엔티티** — `@Column` 이 붙은 필드만 매핑됩니다.

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

`@Table` 이 없으면 클래스명을, `@Column(name = ...)` 이 없으면 필드명을 snake_case 로 바꿔 씁니다. (`removedFlag` → `removed_flag`)

**2. 리포지토리** — 인터페이스만 선언합니다.

```java
@Mapper
@Repository
public interface UserRepository extends MybatisRepository<User> {}
```

자동 증가 PK 가 없는 테이블이라면 `MybatisNoIdRepository<User>` 를 쓰세요. `@Options(useGeneratedKeys = true)` 만 빠졌을 뿐 메서드 구성은 같습니다.

**3. 사용**

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

## 조회 메서드 — 이름이 곧 시그니처

25개 조회 메서드는 아래 조각들의 조합입니다. 필요한 조각만 이어 붙인 이름을 부르면 됩니다.

```
get ⟨Distinct|Target⟩ Items ⟨ByMap⟩ ⟨OrderBy⟩ ⟨LimitOffset⟩
```

| 조각           | 파라미터                    | 생략하면    |
| ------------- | ------------------------- | ---------- |
| `Distinct`    | `Set<String>` 필드명        | 전체 컬럼    |
| `Target`      | `Set<String>` 필드명        | 전체 컬럼    |
| `ByMap`       | `Map<String, Object>` 조건  | 조건 없음    |
| `OrderBy`     | `List<String>` 정렬         | 정렬 없음    |
| `LimitOffset` | `Integer limit, offset`    | 전체 행     |

```java
userRepository.getItems();                                    // 전체
userRepository.getItemsByMap(where);                          // 조건
userRepository.getItemsOrderByLimitOffset(order, 10, 0);      // 정렬 + 페이징
userRepository.getDistinctItemsByMap(Set.of("name"), where);  // DISTINCT
userRepository.getTargetItems(Set.of("id", "name"));          // 특정 컬럼만
```

파라미터는 이름에 등장하는 순서 그대로 넘깁니다. `Distinct` 와 `Target` 을 함께 쓰는 조합은 `getDistinctAndTargetItemsByMapOrderByLimitOffset` 하나만 있습니다.

### 정렬

`List<String>` 에 필드명을 넣고, 내림차순은 `-` 를 앞에 붙입니다.

```java
List.of("name", "-createdAt")   // ORDER BY `name` ASC, `created_at` DESC
```

## 단건 · 개수 · 쓰기

| 메서드                        | 설명                    |
| ---------------------------- | ---------------------- |
| `getItemById(Long)`          | `Optional<T>`          |
| `getItemByMap(Map)`          | `Optional<T>`          |
| `countAll()`                 | 전체 행 수               |
| `countByMap(Map)`            | 조건에 맞는 행 수          |
| `insert(T)`                  | 단건 삽입                |
| `insertBatch(List<T>)`       | 한 문장으로 다건 삽입       |
| `updateById(T, Long)`        | 엔티티의 모든 필드로 갱신    |
| `updateByMap(T, Map)`        | 엔티티의 모든 필드로 조건 갱신 |
| `updateMapById(Map, Long)`   | 지정한 필드만 갱신          |
| `updateMapByMap(Map, Map)`   | 지정한 필드만 조건 갱신      |
| `deleteById(Long)`           | 단건 삭제                |
| `deleteByMap(Map)`           | 조건 삭제                |

## 조건 (WHERE)

키는 `필드명` 또는 `필드명:조건타입` 입니다. 조건타입을 생략하면 `eq` 입니다.

```java
Map.of(
    "removedFlag", false,        // `removed_flag` = false
    "name:contains", "kim",      // INSTR(`name`, 'kim') > 0
    "id:in", Set.of(1L, 2L),     // `id` IN (1, 2)
    "deletedAt:null", null       // `deleted_at` IS NULL
);
```

| 조건타입                    | 생성 SQL                                |
| -------------------------- | -------------------------------------- |
| 생략 · `eq`                 | `` `col` = v ``                        |
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

`in` 과 `notIn` 은 **`Set` 만** 받습니다. `List` 를 넘기면 예외가 발생하고, 기본 상한은 1,000개입니다.

## 설정

시각 값은 바인드 파라미터가 아니라 SQL 리터럴로 그대로 들어갑니다. 그래서 어느 타임존의 벽시계를 찍는지가 곧 DB 에 저장되는 값이 됩니다.

```yaml
mybatis-repository:
  timezone: Asia/Seoul   # 생략하면 UTC
```

지정하면 `Instant`, `OffsetDateTime`, `ZonedDateTime`, ISO8601 문자열, `Date`/`Timestamp` 가 모두 같은 벽시계를 씁니다. 지정하지 않았을 때의 기본값은 타입마다 예전 그대로입니다 -- `Instant` 계열은 `UTC`, `Date`/`Timestamp` 는 JVM 기본 타임존입니다.

이 설정을 따르지 않는 타입도 있습니다. `LocalDateTime`/`LocalDate`/`LocalTime` 은 애초에 벽시계 값이고, `OffsetTime` 은 날짜가 없어 옮길 기준이 없으므로 오프셋만 떼고 찍습니다. JDBC 의 `java.sql.Date`/`java.sql.Time` 은 드라이버나 `valueOf` 가 이미 JVM 기본 타임존으로 정규화해 넣은 값이라 그대로 JVM 기본 타임존으로 읽습니다 -- 여기서 이 설정을 따르면 날짜가 하루씩 밀립니다.

> `@SpringBootTest` 에서는 그대로 동작하지만, `ApplicationContextRunner` 는 `spring.factories` 의 초기화기를 로드하지 않아 이 설정이 적용되지 않습니다. 러너로 검증하려면 `withInitializer(new MybatisTimezoneInitializer())` 를 직접 등록하세요.

> JDBC 는 `ResultSet#getTimestamp` 로 읽을 때 JVM 기본 타임존을 씁니다. 이 설정이 JVM 기본 타임존과 다르면 **저장한 시각과 읽어온 시각이 그 차이만큼 어긋납니다.** 알 수 없는 존 ID 는 조용히 기본값으로 떨어지지 않고 기동을 실패시킵니다.

## 감사 컬럼 (선택)

`createdAt` / `createdBy` / `updatedAt` / `updatedBy` 를 INSERT·UPDATE 때 자동으로 채웁니다. **`MybatisAuditorAware` 빈을 등록해야 켜집니다.** 등록하지 않으면 아무 일도 일어나지 않습니다.

```java
@Component
public class MyAuditorAware implements MybatisAuditorAware {
    @Override
    public Optional<String> getCurrentAuditor() {
        // 세션이 없으면 Optional.empty() 를 돌려주세요. 예외를 던지면 안 됩니다.
        return Optional.ofNullable(currentUserId());
    }
}
```

- **INSERT** — 네 필드를 모두 채웁니다. `insertBatch` 는 모든 행을 같은 시각으로 채웁니다.
- **UPDATE** — `updatedAt` / `updatedBy` 만 채웁니다. 호출부가 `updateMap` 에 넣은 감사 컬럼 값은 위조 방지를 위해 걷어내고, 생성 계열은 다시 쓰지 않습니다. 그 밖의 키는 값이 `null` 이어도 그대로 둡니다(`SET col = null` 규약 유지).
- 엔티티에 그 이름의 `@Column` 필드가 없으면 그냥 건너뜁니다. 감사 컬럼이 없는 엔티티도 정상입니다.
- 감사자가 비어 있으면(`Optional.empty()`) `*_BY` 는 건드리지 않고 일시만 채웁니다. 배치·스케줄러처럼 세션이 없는 경로를 위한 것입니다.
- 호출부가 넘긴 `updateMap` 은 변형하지 않습니다. 공유 상수를 그대로 넘겨도 안전합니다.

필드 이름은 바꿀 수 있습니다. **DB 컬럼명이 아니라 자바 필드명**입니다.

```yaml
mybatis-repository:
  created-at: regDt
  created-by: regId
  updated-at: modDt
  updated-by: modId
```

지원 타입은 일시가 `Instant` · `LocalDateTime` · `java.util.Date` · `java.sql.Timestamp`, 감사자가 `String` 입니다. 그 밖의 타입이면 조용히 넘어가지 않고 예외를 던집니다 -- 감사 컬럼이 소리 없이 비는 것이 가장 나쁜 결과이기 때문입니다.

> `LocalDateTime` 과 `java.util.Date` / `java.sql.Timestamp` 를 한 스키마에서 함께 쓴다면 `mybatis-repository.timezone` 을 반드시 지정하세요. 지정하지 않으면 앞은 UTC 벽시계, 뒤는 JVM 기본 타임존을 따라 같은 행의 두 컬럼이 어긋납니다.

> `SqlSessionFactoryBean` 을 직접 정의했다면 이 인터셉터 빈을 그 `plugins` 에 손수 넣어야 합니다. `mybatis-spring-boot-starter` 가 만든 `SqlSessionFactory` 를 쓰면 자동으로 붙습니다.

## 알아두어야 할 것

- **`@Column` 이 없는 필드는 없는 것으로 취급됩니다.** 조회·삽입·수정 대상에서 빠지고, 조건 Map 에 그 필드명을 넣으면 `MybatisRepositoryException` 이 발생합니다.
- **`...ById` 메서드는 `id` 라는 이름의 `Long` 필드를 전제합니다.** `@Id` 애노테이션은 읽지 않습니다. PK 의 이름이나 타입이 다르면 `...ByMap` 을 쓰세요.
- **UPDATE 와 DELETE 는 조건이 비면 예외를 던집니다.** 전체 갱신·전체 삭제를 실수로 실행할 수 없습니다.
- **알 수 없는 조건타입은 조용히 `eq` 가 됩니다.** `name:contain` 처럼 오타를 내도 오류 없이 동등 비교로 처리됩니다.
- Map 의 키는 컬럼명이 아니라 **자바 필드명(카멜케이스)** 입니다.
- 조회는 `SELECT *` 가 아니라 `@Column` 필드 목록을 명시적으로 나열합니다. 컬럼 순서는 보장되지 않습니다.
- 값은 이스케이프 후 SQL 리터럴로 삽입되고, 식별자는 허용 문자 화이트리스트와 SQL 키워드 차단 목록으로 검증됩니다.

## 기여 방법

이슈와 PR 을 환영합니다. 코드를 수정했다면 `./gradlew spotlessApply` 를 먼저 실행해 주세요.

## 라이선스

[Apache License 2.0](LICENSE)
