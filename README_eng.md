# MyBatis Repository

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bestheroz/mybatis-repository)](https://search.maven.org/artifact/io.github.bestheroz/mybatis-repository)
![GitHub top language](https://img.shields.io/github/languages/top/bestheroz/mybatis-repository)
![Coverage](.github/badges/jacoco.svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/bestheroz/mybatis-repository/blob/main/LICENSE)

## Overview

**MyBatis Repository** is a Java library that simplifies CRUD (Create, Read, Update, Delete) SQL operations by automatically generating and executing SQL statements through MyBatis. By calling predefined functions, developers can significantly reduce boilerplate code and enhance productivity.

## Key Features

* **Generic Repository Interface**: Provides the `MybatisRepository<T>` interface for generic CRUD operations.
* **Dynamic SQL Generation**: Utilizes `MybatisCommand` to dynamically build SQL queries based on input parameters.
* **Flexible Query Methods**: Offers a variety of query methods supporting filtering, sorting, distinct selection, and pagination options.
* **Batch Operations Support**: Includes batch insertion capabilities for inserting multiple entities at once.
* **Configuration Options**: Allows exclusion of specific fields from SQL operations through configuration files.
* **Spring Boot Integration**: Seamlessly integrates with Spring Boot applications.

## Requirements

* **Java**: 1.8 or higher
* **Spring Boot**: 2.x or higher
* **MyBatis Spring Boot Starter**: 2.x or higher
* **Jakarta Persistence API**: 2.x or higher
* *(Optional)* **Kotlin**: 1.x or higher

## Installation

Add the following dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'io.github.bestheroz:mybatis-repository:0.4.0'
}
```

Or add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.bestheroz</groupId>
    <artifactId>mybatis-repository</artifactId>
    <version>0.4.0</version>
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
        long count = this.userRepository.countByMap(Map.of("removedFlag", false));
        List<User> users = this.userRepository.getItemsByMapOrderByLimitOffset(
                Map.of("removedFlag", false),
                List.of("-id"),
                request.getPageSize(),
                (request.getPage() - 1) * request.getPageSize()
            );
        // Additional operations...
    }

    @Transactional(readOnly = true)
    public UserDto.Response getUser(Long id) {
        return this.userRepository.getItemById(id)
            .map(UserDto.Response::of)
            .orElseThrow(() -> new RequestException400(ExceptionCode.UNKNOWN_USER));
    }

    public UserDto.Response createUser(final UserCreateDto.Request request, Operator operator) {
        if (this.userRepository.countByMap(
                Map.of("loginId", request.getLoginId(), "removedFlag", false)
            ) > 0) {
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
      User user = this.userRepository
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
// List<User> users = this.userRepository.getItems()
List<User> users = this.userRepository.getItems();
// SQL: SELECT * FROM users;

// List<User> users = this.userRepository.getItemsLimitOffset(limit, offset)
List<User> users = this.userRepository.getItemsLimitOffset(10, 0);
// SQL: SELECT * FROM users LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getItemsOrderBy(List<String>)
List<User> users = this.userRepository.getItemsOrderBy(List.of("name", "-id"));
// SQL: SELECT * FROM users ORDER BY name ASC, id DESC;

// List<User> users = this.userRepository.getItemsOrderByLimitOffset(List<String>, limit, offset)
List<User> users = this.userRepository.getItemsOrderByLimitOffset(List.of("name", "-createdAt"), 10, 0);
// SQL: SELECT * FROM users ORDER BY name ASC, created_at DESC LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getItemsByMap(Map)
List<User> users = this.userRepository.getItemsByMap(Map.of("removedFlag", false));
// SQL: SELECT * FROM users WHERE removed_flag = false;

// List<User> users = this.userRepository.getItemsByMapLimitOffset(Map, limit, offset)
List<User> users = this.userRepository.getItemsByMapLimitOffset(Map.of("useFlag", true), 10, 0);
// SQL: SELECT * FROM users WHERE use_flag = true LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getItemsByMapOrderBy(Map, List<String>)
List<User> users = this.userRepository.getItemsByMapOrderBy(
        Map.of("removedFlag", false),
        List.of("name", "-id")
);
// SQL: SELECT * FROM users WHERE removed_flag = false ORDER BY name ASC, id DESC;

// List<User> users = this.userRepository.getItemsByMapOrderByLimitOffset(Map, List<String>, limit, offset)
List<User> users = this.userRepository.getItemsByMapOrderByLimitOffset(
        Map.of("useFlag", true),
        List.of("name", "-id"),
        10,
        0
);
// SQL: SELECT * FROM users WHERE use_flag = true ORDER BY name ASC, id DESC LIMIT 10 OFFSET 0;
```

#### 2. DISTINCT Methods

```java
// List<User> users = this.userRepository.getDistinctItems(Set<String>)
List<User> users = this.userRepository.getDistinctItems(Set.of("name", "loginId"));
// SQL: SELECT DISTINCT name, login_id FROM users;

// List<User> users = this.userRepository.getDistinctItemsLimitOffset(Set<String>, limit, offset)
List<User> users = this.userRepository.getDistinctItemsLimitOffset(Set.of("name"), 10, 0);
// SQL: SELECT DISTINCT name FROM users LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getDistinctItemsOrderBy(Set<String>, List<String>)
List<User> users = this.userRepository.getDistinctItemsOrderBy(
        Set.of("name", "loginId"),
        List.of("name", "-loginId")
);
// SQL: SELECT DISTINCT name, login_id FROM users ORDER BY name ASC, login_id DESC;

// List<User> users = this.userRepository.getDistinctItemsOrderByLimitOffset(Set<String>, List<String>, limit, offset)
List<User> users = this.userRepository.getDistinctItemsOrderByLimitOffset(
        Set.of("name", "loginId"),
        List.of("name", "-loginId"),
        10,
        0
);
// SQL: SELECT DISTINCT name, login_id FROM users ORDER BY name ASC, login_id DESC LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getDistinctItemsByMap(Set<String>, Map)
List<User> users = this.userRepository.getDistinctItemsByMap(
        Set.of("name", "loginId"),
        Map.of("removedFlag", false)
);
// SQL: SELECT DISTINCT name, login_id FROM users WHERE removed_flag = false;

// List<User> users = this.userRepository.getDistinctItemsByMapLimitOffset(Set<String>, Map, limit, offset)
List<User> users = this.userRepository.getDistinctItemsByMapLimitOffset(
        Set.of("name"),
        Map.of("removedFlag", false),
        10,
        0
);
// SQL: SELECT DISTINCT name FROM users WHERE removed_flag = false LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getDistinctItemsByMapOrderBy(Set<String>, Map, List<String>)
List<User> users = this.userRepository.getDistinctItemsByMapOrderBy(
        Set.of("name", "loginId"),
        Map.of("useFlag", true),
        List.of("name", "-loginId")
);
// SQL: SELECT DISTINCT name, login_id FROM users WHERE use_flag = true ORDER BY name ASC, login_id DESC;

// List<User> users = this.userRepository.getDistinctItemsByMapOrderByLimitOffset(Set<String>, Map, List<String>, limit, offset)
List<User> users = this.userRepository.getDistinctItemsByMapOrderByLimitOffset(
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
// List<User> users = this.userRepository.getTargetItems(Set<String>)
List<User> users = this.userRepository.getTargetItems(Set.of("id", "name"));
// SQL: SELECT id, name FROM users;

// List<User> users = this.userRepository.getTargetItemsLimitOffset(Set<String>, limit, offset)
List<User> users = this.userRepository.getTargetItemsLimitOffset(Set.of("id", "name"), 10, 0);
// SQL: SELECT id, name FROM users LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getTargetItemsOrderBy(Set<String>, List<String>)
List<User> users = this.userRepository.getTargetItemsOrderBy(
        Set.of("id", "name"),
        List.of("name", "-id")
);
// SQL: SELECT id, name FROM users ORDER BY name ASC, id DESC;

// List<User> users = this.userRepository.getTargetItemsOrderByLimitOffset(Set<String>, List<String>, limit, offset)
List<User> users = this.userRepository.getTargetItemsOrderByLimitOffset(
        Set.of("id", "name"),
        List.of("name", "-id"),
        10,
        0
);
// SQL: SELECT id, name FROM users ORDER BY name ASC, id DESC LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getTargetItemsByMap(Set<String>, Map)
List<User> users = this.userRepository.getTargetItemsByMap(
        Set.of("id", "name"),
        Map.of("removedFlag", false)
);
// SQL: SELECT id, name FROM users WHERE removed_flag = false;

// List<User> users = this.userRepository.getTargetItemsByMapLimitOffset(Set<String>, Map, limit, offset)
List<User> users = this.userRepository.getTargetItemsByMapLimitOffset(
        Set.of("id", "name"),
        Map.of("removedFlag", false),
        10,
        0
);
// SQL: SELECT id, name FROM users WHERE removed_flag = false LIMIT 10 OFFSET 0;

// List<User> users = this.userRepository.getTargetItemsByMapOrderBy(Set<String>, Map, List<String>)
List<User> users = this.userRepository.getTargetItemsByMapOrderBy(
        Set.of("id", "name"),
        Map.of("useFlag", true),
        List.of("name", "-id")
);
// SQL: SELECT id, name FROM users WHERE use_flag = true ORDER BY name ASC, id DESC;

// List<User> users = this.userRepository.getTargetItemsByMapOrderByLimitOffset(Set<String>, Map, List<String>, limit, offset)
List<User> users = this.userRepository.getTargetItemsByMapOrderByLimitOffset(
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
// Optional<User> user = this.userRepository.getItemByMap(Map)
Optional<User> user = this.userRepository.getItemByMap(Map.of(
                "loginId", "developer",
                "removedFlag", false
        ));
// SQL: SELECT * FROM users WHERE login_id = 'developer' AND removed_flag = false;

// Optional<User> user = this.userRepository.getItemById(Long)
Optional<User> user = this.userRepository.getItemById(1L);
// SQL: SELECT * FROM users WHERE id = 1;
```

#### 5. Count Methods

```java
// long count = this.userRepository.countAll()
long count = this.userRepository.countAll();
// SQL: SELECT COUNT(*) FROM users;

// long count = this.userRepository.countByMap(Map)
long count = this.userRepository.countByMap(Map.of("removedFlag", false));
// SQL: SELECT COUNT(*) FROM users WHERE removed_flag = false;
```

#### 6. Insert Methods

```java
// this.userRepository.insert(T)
User user = User.of(
                "developer",
                "password123",
                "개발자",
                true,
                List.of(AuthorityEnum.NOTICE_VIEW),
                operator
        );
this.userRepository.insert(user);
// SQL: INSERT INTO users (...) VALUES (...);

// this.userRepository.insertBatch(List<T>)
List<User> users = List.of(user1, user2, user3);
this.userRepository.insertBatch(users);
// SQL: INSERT INTO users (...) VALUES (...), (...), (...);
```

#### 7. Update Methods

```java
// this.userRepository.updateById(T, Long)
this.userRepository.updateById(user, 1L);
// SQL: UPDATE users SET ... WHERE id = 1;

// this.userRepository.updateByMap(T, Map)
this.userRepository.updateByMap(
        user,
        Map.of("loginId", "developer")
);
// SQL: UPDATE users SET ... WHERE login_id = 'developer';

// this.userRepository.updateMapByMap(Map, Map)
        this.userRepository.updateMapByMap(
        Map.of("useFlag", false),
    Map.of("removedFlag", true)
);
// SQL: UPDATE users SET use_flag = false WHERE removed_flag = true;

// this.userRepository.updateMapById(Map, Long)
        this.userRepository.updateMapById(
        Map.of("useFlag", false),
    1L
            );
// SQL: UPDATE users SET use_flag = false WHERE id = 1;
```

#### 8. Delete Methods

```java
// this.userRepository.deleteByMap(Map)
this.userRepository.deleteByMap(Map.of("removedFlag", true));
// SQL: DELETE FROM users WHERE removed_flag = true;

// this.userRepository.deleteById(Long)
        this.userRepository.deleteById(1L);
// SQL: DELETE FROM users WHERE id = 1;
```

#### 9. Condition Types

The `MybatisRepository` supports various condition types to build flexible and dynamic queries. Below are the supported condition types along with examples and the corresponding SQL generated.

* **Equality and Inequality**

  ```java
  // Equal to
  Map<String, Object> conditions = Map.of("name", "John");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE name = 'John';

  // Not equal to
  Map<String, Object> conditions = Map.of("name:not", "John");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE name <> 'John';
  ```

* **IN and NOT IN**

  ```java
  // In
  Map<String, Object> conditions = Map.of("id:in", Set.of(1L, 2L, 3L));
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE id IN (1, 2, 3);

  // Not In
  Map<String, Object> conditions = Map.of("id:notIn", Set.of(1L, 2L, 3L));
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE id NOT IN (1, 2, 3);
  ```

* **NULL and NOT NULL**

  ```java
  // Is NULL
  Map<String, Object> conditions = Map.of("deletedAt:null", null);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE deleted_at IS NULL;

  // Is NOT NULL
  Map<String, Object> conditions = Map.of("deletedAt:notNull", null);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE deleted_at IS NOT NULL;
  ```

* **String Operations**

  ```java
  // Contains (substring)
  Map<String, Object> conditions = Map.of("description:contains", "admin");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`description`, 'admin') > 0;

  // Does Not Contain (substring)
  Map<String, Object> conditions = Map.of("description:notContains", "admin");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`description`, 'admin') = 0;

  // Starts With
  Map<String, Object> conditions = Map.of("username:startsWith", "john");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE INSTR(`username`, 'john') = 1;

  // Ends With
  Map<String, Object> conditions = Map.of("username:endsWith", "doe");
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE RIGHT(`username`, CHAR_LENGTH('doe')) = 'doe';
  ```

* **Comparison Operators**

  ```java
  // Less Than
  Map<String, Object> conditions = Map.of("age:lt", 30);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age < 30;

  // Less Than or Equal To
  Map<String, Object> conditions = Map.of("age:lte", 30);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age <= 30;

  // Greater Than
  Map<String, Object> conditions = Map.of("age:gt", 20);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age > 20;

  // Greater Than or Equal To
  Map<String, Object> conditions = Map.of("age:gte", 20);
  this.userRepository.getItemsByMap(conditions);
  // SQL: SELECT * FROM users WHERE age >= 20;
  ```

* **Default Equality**

  If no condition type is specified, the default is equality (`eq`).

  ```java
  // Equal to (default)
  Map<String, Object> conditions = Map.of("email", "bestheroz@gmail.com");
  this.userRepository.getItemByMap(conditions);
  // SQL: SELECT * FROM users WHERE email = 'bestheroz@gmail.com';
  ```

**Notes:**

1. The keys in the `Map` should be written in camelCase (automatically converted to snake\_case).
2. For sorting conditions, providing only the column name defaults to ascending order; prefixing with `-` indicates descending order.
3. Passing `null` is treated as an empty collection.
4. Providing incorrect column names or formats will result in SQL exceptions.

## Example

### Define Entity

```java
package com.github.bestheroz.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "users")
public class User {
  private Long id;
  private String loginId;
  private String password;

  @Column(name = "username")
  private String name; 

  private Boolean useFlag;

  @Column(name = "is_removed")
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
    return this.userRepository.getItemById(id)
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
