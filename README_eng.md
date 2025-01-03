# MyBatis Repository

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/bestheroz/mybatis-repository/blob/main/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.bestheroz/mybatis-repository)](https://search.maven.org/artifact/io.github.bestheroz/mybatis-repository)

## Korean Version

[README - Korean Version](https://github.com/bestheroz/mybatis-repository/blob/main/README_ko.md)

## Overview

**MyBatis Repository** is a Java library that simplifies CRUD (Create, Read, Update, Delete) SQL operations by automatically generating and executing SQL statements through MyBatis. By calling predefined functions, developers can significantly reduce boilerplate code and enhance productivity.

## Key Features

- **Generic Repository Interface**: Provides the `MybatisRepository<T>` interface for generic CRUD operations.
- **Dynamic SQL Generation**: Utilizes `MybatisCommand` to dynamically build SQL queries based on input parameters.
- **Flexible Query Methods**: Offers a variety of query methods supporting filtering, sorting, distinct selection, and pagination options.
- **Batch Operations Support**: Includes batch insertion capabilities for inserting multiple entities at once.
- **Configuration Options**: Allows exclusion of specific fields from SQL operations through configuration files.
- **Spring Boot Integration**: Seamlessly integrates with Spring Boot applications.

## Requirements

- **Java**: 1.8 or higher
- **Spring Boot**: 2.x or higher
- **MyBatis Spring Boot Starter**: 2.x or higher
- **Jakarta Persistence API**: 2.x or higher
- *(Optional)* **Kotlin**: 1.x or higher

## Installation

Add the following dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'io.github.bestheroz:mybatis-repository:0.2.11'
}
```

Or add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.bestheroz</groupId>
    <artifactId>mybatis-repository</artifactId>
    <version>0.2.11</version>
</dependency>
```

## Configuration

### `application.yml`

Configure the repository to exclude specific fields from SQL operations:

```yaml
mybatis-repository:
  exclude-fields:
    - updatedByAdmin
    - updatedByUser
    - createdByAdmin
    - createdByUser
    - Companion(with kotlin)
```

## Usage

### Define Repository

Create a repository interface for your entity by extending `MybatisRepository<T>`.

```java
@Mapper
@Repository
public interface UserRepository extends MybatisRepository<User> {}
```

### Create Service

Utilize the repository in the service layer to perform CRUD operations.

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
        // Additional operations...
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
        // Additional operations...
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
        // Additional operations...
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
        // Additional operations...
    }

    // Additional CRUD methods...
}
```

### Available Repository Methods

#### 1. Basic Query Methods

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

#### 2. DISTINCT Methods

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

#### 3. Target Column Methods

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

#### 4. Single Item Query

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

#### 5. Count Methods

```java
// countAll()
countAll();
// SQL: SELECT COUNT(*) FROM users;

// countByMap(Map)
countByMap(Map.of("removedFlag", false));
// SQL: SELECT COUNT(*) FROM users WHERE removed_flag = false;
```

#### 6. Insert Methods

```java
// insert(T)
User user = User.of(
    "developer",
    "password123",
    "Developer",
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

#### 7. Update Methods

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

#### 8. Delete Methods

```java
// deleteByMap(Map)
deleteByMap(Map.of("removedFlag", true));
// SQL: DELETE FROM users WHERE removed_flag = true;

// deleteById(Long)
deleteById(1L);
// SQL: DELETE FROM users WHERE id = 1;
```

**Notes:**
1. The keys in the `Map` should be written in camelCase (automatically converted to snake_case).
2. For sorting conditions, providing only the column name defaults to ascending order; prefixing with `-` indicates descending order.
3. Passing `null` is treated as an empty collection.
4. Providing incorrect column names or formats will result in SQL exceptions.

## Example

### Define Entity

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
    // Additional fields and methods...
}
```

### Create Repository

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

### Implement Service

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

    // Additional service methods...
}
```

## How to Contribute

Contributions are welcome! Please fork the repository and submit a pull request with your improvements or bug fixes.

## License

This project is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt).

---

*❤️ Developed with love by [bestheroz](https://github.com/bestheroz)*
