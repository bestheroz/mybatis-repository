# MyBatis Repository

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/bestheroz/mybatis-repository/blob/main/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.bestheroz/mybatis-repository)](https://search.maven.org/artifact/io.github.bestheroz/mybatis-repository)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

## 개요

**MyBatis Repository**는 MyBatis를 통해 간단한 CRUD(생성, 조회, 업데이트, 삭제) SQL 작업을 자동으로 생성하고 실행할 수 있도록 도와주는 자바 라이브러리입니다. 사전에 정의된 함수를 호출함으로써 개발자는 보일러플레이트 코드를 크게 줄이고 생산성을 향상시킬 수 있습니다.

## 주요 기능

- **제네릭 리포지토리 인터페이스**: 제네릭 CRUD 작업을 위한 `MybatisRepository<T>` 인터페이스 제공.
- **동적 SQL 생성**: `MybatisCommand`를 사용하여 입력 파라미터 기반으로 동적으로 SQL 쿼리 빌드.
- **유연한 쿼리 메서드**: 필터링, 정렬, 중복 선택, 페이징 등의 옵션을 지원하는 다양한 쿼리 메서드 제공.
- **배치 작업 지원**: 다수의 엔티티를 한 번에 삽입할 수 있는 배치 삽입 기능.
- **구성 옵션**: 구성 파일을 통해 특정 필드를 SQL 작업에서 제외할 수 있음.
- **Spring Boot와의 통합**: Spring Boot 애플리케이션과 원활하게 통합.

## 요구 사항

- **Java**: 1.8 이상
- **Spring Boot**: 2.x 이상
- **MyBatis Spring Boot Starter**: 2.x 이상
- **Jakarta Persistence API**: 2.x 이상
- (추가지원) **Kotlin**: 1.x 이상

## 설치 방법

`build.gradle`에 다음 의존성을 추가하세요:

```groovy
dependencies {
    implementation 'io.github.bestheroz:mybatis-repository:0.3.3'
}
```

또는 `pom.xml`에 다음 의존성을 추가하세요:

```xml
<dependency>
    <groupId>io.github.bestheroz</groupId>
    <artifactId>mybatis-repository</artifactId>
    <version>0.3.3</version>
</dependency>
```

## 구성 방법

### `application.yml`

SQL 작업에서 특정 필드를 제외하도록 리포지토리를 구성합니다:

```yaml
mybatis-repository:
  exclude-fields:
    - updatedByAdmin
    - updatedByUser
    - createdByAdmin
    - createdByUser
    - Companion(with kotlin)
```

## 사용 방법

### 리포지토리 정의

엔티티에 대한 리포지토리 인터페이스를 `MybatisRepository<T>`를 확장하여 생성합니다.

```java
@Mapper
@Repository
public interface UserRepository extends MybatisRepository<User> {}
```

### 서비스 생성

서비스 계층에서 리포지토리를 활용하여 CRUD 작업을 수행합니다.

```java
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public ListResult<UserDto.Response> getUserList(UserDto.Request request) {
        long count = userRepository.countByMap(Map.of("removedFlag", false));
        List<UserDto.Response> userDtoResponseList =
            userRepository.getItemsByMapOrderByLimitOffset(
                Map.of("removedFlag", false),
                List.of("-id"),
                request.getPageSize(),
                (request.getPage() - 1) * request.getPageSize());
        // 추가적인 작업...
    }

    @Transactional(readOnly = true)
    public UserDto.Response getUser(Long id) {
        return userRepository.getItemById(id)
            .map(UserDto.Response::of)
            .orElseThrow(() -> new RequestException400(ExceptionCode.UNKNOWN_USER));
    }

    public UserDto.Response createUser(final UserCreateDto.Request request, Operator operator) {
        if (this.userRepository.countByMap(
                Map.of("loginId", request.getLoginId(), "removedFlag", false))
                > 0) {
            throw new RequestException400(ExceptionCode.ALREADY_JOINED_ACCOUNT);
        }
        User user = request.toEntity(operator);
        this.userRepository.insert(user);
        // 추가적인 작업...
    }

    public UserDto.Response updateUser(
            final Long id, final UserUpdateDto.Request request, Operator operator) {
        User user =
                this.userRepository
                        .getItemById(id)
                        .orElseThrow(() -> new RequestException400(ExceptionCode.UNKNOWN_USER));
        if (user.getRemovedFlag()) throw new RequestException400(ExceptionCode.UNKNOWN_USER);

        if (this.userRepository.countByMap(
                Map.of("loginId", request.getLoginId(), "removedFlag", false, "id:not", id))
                > 0) {
            throw new RequestException400(ExceptionCode.ALREADY_JOINED_ACCOUNT);
        }

        user.update(
                request.getLoginId(),
                request.getPassword(),
                request.getName(),
                request.getUseFlag(),
                request.getAuthorities(),
                operator);
        this.userRepository.updateById(user, user.getId());
        // 추가적인 작업...
    }

    public void deleteUser(final Long id, Operator operator) {
        User user =
                this.userRepository
                        .getItemById(id)
                        .orElseThrow(() -> new RequestException400(ExceptionCode.UNKNOWN_USER));
        if (user.getRemovedFlag()) throw new RequestException400(ExceptionCode.UNKNOWN_USER);
        if (user.getId().equals(operator.getId())) {
            throw new RequestException400(ExceptionCode.CANNOT_REMOVE_YOURSELF);
        }
        user.remove(operator);
        this.userRepository.updateById(user, user.getId());
        // 추가적인 작업...
    }

    // 추가적인 CRUD 메서드...
}
```

### 사용 가능한 리포지토리 메서드

#### 1. 기본 조회 메서드

```java
// getItems()
getItems();
// SQL: SELECT * FROM users;

// getItemsLimitOffset(limit, offset)
getItemsLimitOffset(10, 0);
// SQL: SELECT * FROM users LIMIT 10 OFFSET 0;

// getItemsOrderBy(List<String>)
getItemsOrderBy(List.of("name", "-id"));
// SQL: SELECT * FROM users ORDER BY name ASC, id DESC;

// getItemsOrderByLimitOffset(List<String>, limit, offset)
getItemsOrderByLimitOffset(List.of("name", "-createdAt"), 10, 0);
// SQL: SELECT * FROM users ORDER BY name ASC, created_at DESC LIMIT 10 OFFSET 0;

// getItemsByMap(Map)
getItemsByMap(Map.of("removedFlag", false));
// SQL: SELECT * FROM users WHERE removed_flag = false;

// getItemsByMapLimitOffset(Map, limit, offset)
getItemsByMapLimitOffset(Map.of("useFlag", true), 10, 0);
// SQL: SELECT * FROM users WHERE use_flag = true LIMIT 10 OFFSET 0;

// getItemsByMapOrderBy(Map, List<String>)
getItemsByMapOrderBy(
    Map.of("removedFlag", false), 
    List.of("name", "-id")
);
// SQL: SELECT * FROM users WHERE removed_flag = false ORDER BY name ASC, id DESC;

// getItemsByMapOrderByLimitOffset(Map, List<String>, limit, offset)
getItemsByMapOrderByLimitOffset(
    Map.of("useFlag", true), 
    List.of("name", "-id"),
    10, 
    0
);
// SQL: SELECT * FROM users WHERE use_flag = true ORDER BY name ASC, id DESC LIMIT 10 OFFSET 0;
```

#### 2. DISTINCT 메서드

```java
// getDistinctItems(Set<String>)
getDistinctItems(Set.of("name", "loginId"));
// SQL: SELECT DISTINCT name, login_id FROM users;

// getDistinctItemsLimitOffset(Set<String>, limit, offset)
getDistinctItemsLimitOffset(Set.of("name"), 10, 0);
// SQL: SELECT DISTINCT name FROM users LIMIT 10 OFFSET 0;

// getDistinctItemsOrderBy(Set<String>, List<String>)
getDistinctItemsOrderBy(
    Set.of("name", "loginId"),
    List.of("name", "-loginId")
);
// SQL: SELECT DISTINCT name, login_id FROM users ORDER BY name ASC, login_id DESC;

// getDistinctItemsOrderByLimitOffset(Set<String>, List<String>, limit, offset)
getDistinctItemsOrderByLimitOffset(
    Set.of("name", "loginId"),
    List.of("name", "-loginId"),
    10,
    0
);
// SQL: SELECT DISTINCT name, login_id FROM users ORDER BY name ASC, login_id DESC LIMIT 10 OFFSET 0;

// getDistinctItemsByMap(Set<String>, Map)
getDistinctItemsByMap(
    Set.of("name", "loginId"),
    Map.of("removedFlag", false)
);
// SQL: SELECT DISTINCT name, login_id FROM users WHERE removed_flag = false;

// getDistinctItemsByMapLimitOffset(Set<String>, Map, limit, offset)
getDistinctItemsByMapLimitOffset(
    Set.of("name"),
    Map.of("removedFlag", false),
    10,
    0
);
// SQL: SELECT DISTINCT name FROM users WHERE removed_flag = false LIMIT 10 OFFSET 0;

// getDistinctItemsByMapOrderBy(Set<String>, Map, List<String>)
getDistinctItemsByMapOrderBy(
    Set.of("name", "loginId"),
    Map.of("useFlag", true),
    List.of("name", "-loginId")
);
// SQL: SELECT DISTINCT name, login_id FROM users WHERE use_flag = true ORDER BY name ASC, login_id DESC;

// getDistinctItemsByMapOrderByLimitOffset(Set<String>, Map, List<String>, limit, offset)
getDistinctItemsByMapOrderByLimitOffset(
    Set.of("name", "loginId"),
    Map.of("useFlag", true),
    List.of("name", "-loginId"),
    10,
    0
);
// SQL: SELECT DISTINCT name, login_id FROM users WHERE use_flag = true ORDER BY name ASC, login_id DESC LIMIT 10 OFFSET 0;
```

#### 3. Target 컬럼 메서드

```java
// getTargetItems(Set<String>)
getTargetItems(Set.of("id", "name"));
// SQL: SELECT id, name FROM users;

// getTargetItemsLimitOffset(Set<String>, limit, offset)
getTargetItemsLimitOffset(Set.of("id", "name"), 10, 0);
// SQL: SELECT id, name FROM users LIMIT 10 OFFSET 0;

// getTargetItemsOrderBy(Set<String>, List<String>)
getTargetItemsOrderBy(
    Set.of("id", "name"),
    List.of("name", "-id")
);
// SQL: SELECT id, name FROM users ORDER BY name ASC, id DESC;

// getTargetItemsOrderByLimitOffset(Set<String>, List<String>, limit, offset)
getTargetItemsOrderByLimitOffset(
    Set.of("id", "name"),
    List.of("name", "-id"),
    10,
    0
);
// SQL: SELECT id, name FROM users ORDER BY name ASC, id DESC LIMIT 10 OFFSET 0;

// getTargetItemsByMap(Set<String>, Map)
getTargetItemsByMap(
    Set.of("id", "name"),
    Map.of("removedFlag", false)
);
// SQL: SELECT id, name FROM users WHERE removed_flag = false;

// getTargetItemsByMapLimitOffset(Set<String>, Map, limit, offset)
getTargetItemsByMapLimitOffset(
    Set.of("id", "name"),
    Map.of("removedFlag", false),
    10,
    0
);
// SQL: SELECT id, name FROM users WHERE removed_flag = false LIMIT 10 OFFSET 0;

// getTargetItemsByMapOrderBy(Set<String>, Map, List<String>)
getTargetItemsByMapOrderBy(
    Set.of("id", "name"),
    Map.of("useFlag", true),
    List.of("name", "-id")
);
// SQL: SELECT id, name FROM users WHERE use_flag = true ORDER BY name ASC, id DESC;

// getTargetItemsByMapOrderByLimitOffset(Set<String>, Map, List<String>, limit, offset)
getTargetItemsByMapOrderByLimitOffset(
    Set.of("id", "name"),
    Map.of("useFlag", true),
    List.of("name", "-id"),
    10,
    0
);
// SQL: SELECT id, name FROM users WHERE use_flag = true ORDER BY name ASC, id DESC LIMIT 10 OFFSET 0;
```

#### 4. 단일 아이템 조회

```java
// getItemByMap(Map)
getItemByMap(Map.of(
    "loginId", "developer",
    "removedFlag", false
));
// SQL: SELECT * FROM users WHERE login_id = 'developer' AND removed_flag = false;

// getItemById(Long)
getItemById(1L);
// SQL: SELECT * FROM users WHERE id = 1;
```

#### 5. 카운트 메서드

```java
// countAll()
countAll();
// SQL: SELECT COUNT(*) FROM users;

// countByMap(Map)
countByMap(Map.of("removedFlag", false));
// SQL: SELECT COUNT(*) FROM users WHERE removed_flag = false;
```

#### 6. 삽입 메서드

```java
// insert(T)
User user = User.of(
    "developer",
    "password123",
    "개발자",
    true,
    List.of(AuthorityEnum.NOTICE_VIEW),
    operator
);
insert(user);
// SQL: INSERT INTO users (...) VALUES (...);

// insertBatch(List<T>)
List<User> users = List.of(user1, user2, user3);
insertBatch(users);
// SQL: INSERT INTO users (...) VALUES (...), (...), (...);
```

#### 7. 업데이트 메서드

```java
// updateById(T, Long)
updateById(user, 1L);
// SQL: UPDATE users SET ... WHERE id = 1;

// updateByMap(T, Map)
updateByMap(
    user,
    Map.of("loginId", "developer")
);
// SQL: UPDATE users SET ... WHERE login_id = 'developer';

// updateMapByMap(Map, Map)
updateMapByMap(
    Map.of("useFlag", false),
    Map.of("removedFlag", true)
);
// SQL: UPDATE users SET use_flag = false WHERE removed_flag = true;

// updateMapById(Map, Long)
updateMapById(
    Map.of("useFlag", false),
    1L
);
// SQL: UPDATE users SET use_flag = false WHERE id = 1;
```

#### 8. 삭제 메서드

```java
// deleteByMap(Map)
deleteByMap(Map.of("removedFlag", true));
// SQL: DELETE FROM users WHERE removed_flag = true;

// deleteById(Long)
deleteById(1L);
// SQL: DELETE FROM users WHERE id = 1;
```

#### 9. 조건 타입 (Condition Types)

`MybatisRepository`는 다양한 조건 타입을 지원하여 유연하고 동적인 쿼리를 구축할 수 있습니다. 아래는 지원되는 조건 타입과 예제, 해당 SQL 문입니다.

- **동등성 및 부등등성 (Equality and Inequality)**

  ```java
  // 동등 조건
  Map<String, Object> conditions = Map.of("name", "John");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE name = 'John';

  // 부등 조건
  Map<String, Object> conditions = Map.of("name:not", "John");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE name <> 'John';
  ```

- **IN 및 NOT IN**

  ```java
  // IN 조건
  Map<String, Object> conditions = Map.of("id:in", Set.of(1L, 2L, 3L));
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE id IN (1, 2, 3);

  // NOT IN 조건
  Map<String, Object> conditions = Map.of("id:notIn", Set.of(1L, 2L, 3L));
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE id NOT IN (1, 2, 3);
  ```

- **NULL 및 NOT NULL**

  ```java
  // IS NULL 조건
  Map<String, Object> conditions = Map.of("deletedAt:null", null);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE deleted_at IS NULL;

  // IS NOT NULL 조건
  Map<String, Object> conditions = Map.of("deletedAt:notNull", null);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE deleted_at IS NOT NULL;
  ```

- **문자열 연산 (String Operations)**

  ```java
  // 포함 (substring)
  Map<String, Object> conditions = Map.of("description:contains", "admin");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`description`, 'admin') > 0;

  // 포함하지 않음 (substring)
  Map<String, Object> conditions = Map.of("description:notContains", "admin");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`description`, 'admin') = 0;

  // 시작 (startsWith)
  Map<String, Object> conditions = Map.of("username:startsWith", "john");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`username`, 'john') = 1;

  // 끝 (endsWith)
  Map<String, Object> conditions = Map.of("username:endsWith", "doe");
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE RIGHT(`username`, CHAR_LENGTH('doe')) = 'doe';
  ```

- **비교 연산자 (Comparison Operators)**

  ```java
  // 미만 (Less Than)
  Map<String, Object> conditions = Map.of("age:lt", 30);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age < 30;

  // 이하 (Less Than or Equal To)
  Map<String, Object> conditions = Map.of("age:lte", 30);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age <= 30;

  // 초과 (Greater Than)
  Map<String, Object> conditions = Map.of("age:gt", 20);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age > 20;

  // 이상 (Greater Than or Equal To)
  Map<String, Object> conditions = Map.of("age:gte", 20);
  getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age >= 20;
  ```

- **기본 동등성 (Default Equality)**

  조건 타입이 지정되지 않은 경우 기본적으로 동등 조건 (`eq`)이 적용됩니다.

  ```java
  // 동등 조건 (기본)
  Map<String, Object> conditions = Map.of("email", "bestheroz@gmail.com");
  getItemByMap(conditions);
  // SQL: SELECT * FROM users WHERE email = 'bestheroz@gmail.com';
  ```

**주의사항:**
1. `Map`의 key는 카멜케이스로 작성되어야 하며, 자동으로 스네이크케이스로 변환됩니다.
2. 정렬 조건에서 컬럼명만 입력할 경우 기본적으로 오름차순(`ASC`)으로 정렬되며, `-`를 접두사로 붙이면 내림차순(`DESC`)으로 정렬됩니다.
3. `null`을 전달할 경우 빈 컬렉션으로 처리됩니다.
4. 잘못된 컬럼명이나 형식을 전달할 경우 SQL 예외가 발생합니다.

## 예제

### 엔티티 정의

```java
package com.github.bestheroz.demo.domain;

import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "users")
public class User {
    private Long id;
    private String loginId;
    private String password;
    private String name;
    private Boolean useFlag;
    private Boolean removedFlag;
    // 추가 필드 및 메서드...
}
```

### 리포지토리 생성

```java
package com.github.bestheroz.demo.repository;

import com.github.bestheroz.demo.domain.User;
import io.github.bestheroz.mybatis.MybatisRepository;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface UserRepository extends MybatisRepository<User> {}
```

### 서비스 구현

```java
package com.github.bestheroz.demo.services;

import com.github.bestheroz.demo.domain.User;
import com.github.bestheroz.demo.dtos.user.UserDto;
import com.github.bestheroz.demo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserDto.Response getUser(Long id) {
        return userRepository.getItemById(id)
            .map(UserDto.Response::of)
            .orElseThrow(() -> new RequestException400(ExceptionCode.UNKNOWN_USER));
    }

    // 추가적인 서비스 메서드...
}
```

## 기여 방법

기여를 환영합니다! 저장소를 포크한 후 개선 사항이나 버그 수정을 위한 풀 리퀘스트를 제출해주세요.

## 라이선스

이 프로젝트는 [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) 하에 라이선스가 부여되었습니다.

---

*❤️로 개발된 프로젝트 by [bestheroz](https://github.com/bestheroz)*
