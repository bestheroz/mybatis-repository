package com.github.bestheroz.mybatis;

import com.github.bestheroz.standard.common.util.LogUtils;
import jakarta.persistence.Table;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.ibatis.jdbc.SQL;
import org.springframework.lang.NonNull;

import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class MybatisCommand {
  public static final String SELECT_ITEMS = "getDistinctAndTargetItemsByMapOrderByLimitOffset";
  public static final String SELECT_ITEM_BY_MAP = "getItemByMap";
  public static final String COUNT_BY_MAP = "countByMap";
  public static final String INSERT = "insert";
  public static final String INSERT_BATCH = "insertBatch";
  public static final String UPDATE_MAP_BY_MAP = "updateMapByMap";
  public static final String DELETE_BY_MAP = "deleteByMap";

  public MybatisCommand() {}

  private static final Set<String> METHOD_LIST =
      Set.of(
          SELECT_ITEMS,
          SELECT_ITEM_BY_MAP,
          COUNT_BY_MAP,
          INSERT,
          INSERT_BATCH,
          UPDATE_MAP_BY_MAP,
          DELETE_BY_MAP);

  public String getTableName() {
    return getTableName(this.getEntityClass());
  }

  public static String getTableName(final Class<?> entityClass) {
    Table tableNameAnnotation = entityClass.getAnnotation(Table.class);
    if (tableNameAnnotation != null) {
      return tableNameAnnotation.name();
    }
    return getCamelCaseToSnakeCase(entityClass.getSimpleName()).toLowerCase();
  }

  private void verifyWhereKey(final Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      log.warn("whereConditions is empty");
      throw new RuntimeException("'where' Conditions is required");
    }
  }

  public String countByMap(final Map<String, Object> whereConditions) {
    final SQL sql = new SQL();
    sql.SELECT("COUNT(1) AS CNT").FROM(this.getTableName());
    this.getWhereSql(sql, whereConditions);
    //    log.debug(sql.toString());
    return sql.toString();
  }

  private Class<?> getEntityClass() {
    return Arrays.stream(new Throwable().getStackTrace())
        .filter(this::isValidStackTraceElement)
        .findFirst()
        .map(this::getClassFromStackTraceElement)
        .orElseThrow(
            () -> {
              log.warn("stackTraceElements is Empty");
              return new RuntimeException("stackTraceElements is required");
            });
  }

  private boolean isValidStackTraceElement(StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      return METHOD_LIST.contains(element.getMethodName())
          && clazz.getInterfaces().length > 0
          && clazz.getInterfaces()[0].getGenericInterfaces().length > 0;
    } catch (ClassNotFoundException e) {
      log.warn(LogUtils.getStackTrace(e));
      return false;
    }
  }

  private Class<?> getClassFromStackTraceElement(StackTraceElement element) {
    try {
      return Class.forName(
          StringUtils.substringBetween(
              Class.forName(element.getClassName())
                  .getInterfaces()[0]
                  .getGenericInterfaces()[0]
                  .getTypeName(),
              "<",
              ">"));
    } catch (ClassNotFoundException e) {
      log.warn(LogUtils.getStackTrace(e));
      throw new RuntimeException("Failed::ClassNotFoundException", e);
    }
  }

  private <T> Set<String> getEntityFields(final Class<T> entity) {
    return Stream.of(getAllFields(entity))
        .map(Field::getName)
        .distinct()
        .filter(fieldName -> !MybatisProperties.getExcludeFields().contains(fieldName))
        .collect(Collectors.toSet());
  }

  private Set<String> getEntityFields() {
    return this.getEntityFields(this.getEntityClass());
  }

  public String getDistinctAndTargetItemsByMapOrderByLimitOffset(
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    final SQL sql = new SQL();

    if (distinctColumns.isEmpty() && targetColumns.isEmpty()) {
      this.getEntityFields()
          .forEach(field -> sql.SELECT(this.wrapIdentifier(getCamelCaseToSnakeCase(field))));
    } else {
      distinctColumns.forEach(
          column -> sql.SELECT_DISTINCT(this.wrapIdentifier(getCamelCaseToSnakeCase(column))));
      targetColumns.stream()
          .filter(column -> !distinctColumns.contains(column))
          .forEach(column -> sql.SELECT(this.wrapIdentifier(getCamelCaseToSnakeCase(column))));
    }

    sql.FROM(this.getTableName());
    if (limit != null) {
      sql.LIMIT(limit);
    }
    if (offset != null) {
      sql.OFFSET(offset);
    }
    this.getWhereSql(sql, whereConditions);

    orderByConditions.forEach(
        condition -> {
          String column = getCamelCaseToSnakeCase(condition);
          sql.ORDER_BY(
              column.startsWith("-")
                  ? this.wrapIdentifier(column.substring(1)) + " desc"
                  : this.wrapIdentifier(column));
        });

    //    log.debug(sql.toString());
    return sql.toString();
  }

  public String getItemByMap(final Map<String, Object> whereConditions) {
    this.verifyWhereKey(whereConditions);
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
        Set.of(), Set.of(), whereConditions, List.of(), null, null);
  }

  public <T> String insert(@NonNull final T entity) {
    final SQL sql = new SQL();
    sql.INSERT_INTO(getTableName(entity.getClass()));
    toMap(entity)
        .forEach(
            (key, value) -> {
              if (!MybatisProperties.getExcludeFields().contains(key)) {
                sql.VALUES(
                    this.wrapIdentifier(getCamelCaseToSnakeCase(key)),
                    this.getFormattedValue(value));
              }
            });

    //    log.debug(sql.toString());
    return sql.toString();
  }

  public <T> String insertBatch(@NonNull final List<T> entities) {
    if (entities.isEmpty()) {
      log.warn("entities are empty");
      throw new RuntimeException("entities empty");
    }
    final SQL sql = new SQL();
    sql.INSERT_INTO(this.wrapIdentifier(getTableName(entities.getFirst().getClass())));
    final Set<String> columns = this.getEntityFields(entities.getFirst().getClass());

    sql.INTO_COLUMNS(
        columns.stream()
            .filter(item -> !MybatisProperties.getExcludeFields().contains(item))
            .map(str -> this.wrapIdentifier(getCamelCaseToSnakeCase(str)))
            .collect(Collectors.joining(", ")));

    final List<ArrayList<String>> valuesList =
        entities.stream()
            .map(MybatisCommand::toMap)
            .map(
                entity ->
                    new ArrayList<>(
                        columns.stream()
                            .map(column -> this.getFormattedValue(entity.get(column)))
                            .toList()))
            .toList();
    sql.INTO_VALUES(
        valuesList.stream()
            .map(value -> StringUtils.join(value, ", "))
            .collect(Collectors.joining("), (")));
    //    log.debug(sql.toString());
    return sql.toString();
  }

  public String updateMapByMap(
      final Map<String, Object> updateMap, final Map<String, Object> whereConditions) {
    this.verifyWhereKey(whereConditions);

    final SQL sql = new SQL();
    sql.UPDATE(this.getTableName());
    updateMap.forEach(
        (javaFieldName, value) -> {
          if (!MybatisProperties.getExcludeFields().contains(javaFieldName)) {
            sql.SET(this.getEqualSql(getCamelCaseToSnakeCase(javaFieldName), value));
          }
        });

    this.getWhereSql(sql, whereConditions);
    if (!StringUtils.containsIgnoreCase(sql.toString(), "WHERE ")) {
      log.warn("whereConditions are empty");
      throw new RuntimeException("whereConditions are empty");
    }
    //    log.debug(sql.toString());
    return sql.toString();
  }

  public String deleteByMap(final Map<String, Object> whereConditions) {
    this.verifyWhereKey(whereConditions);
    final SQL sql = new SQL();
    sql.DELETE_FROM(this.getTableName());
    this.getWhereSql(sql, whereConditions);
    this.requiredWhereConditions(sql);
    //    log.debug(sql.toString());
    return sql.toString();
  }

  private void requiredWhereConditions(final SQL sql) {
    if (!StringUtils.containsIgnoreCase(sql.toString(), "WHERE ")) {
      log.warn("whereConditions are empty");
      throw new RuntimeException("whereConditions are required");
    }
  }

  private String getWhereString(
      final String conditionType, final String dbColumnName, final Object value) {
    switch (conditionType) {
      case "ne":
      case "not":
        return MessageFormat.format("`{0}` <> {1}", dbColumnName, this.getFormattedValue(value));
      case "in":
        {
          if (value instanceof List) {
            log.warn("conditionType 'in' is require Set");
            throw new RuntimeException(
                "conditionType 'in' is require Set, yours : " + value.getClass());
          }
          final Set<?> values = (Set<?>) value;
          if (values.isEmpty()) {
            log.warn("WHERE - empty in cause : {}", dbColumnName);
            throw new RuntimeException("WHERE - empty in cause : " + dbColumnName);
          }
          return MessageFormat.format(
              "`{0}` IN ({1})",
              dbColumnName,
              values.stream().map(this::getFormattedValue).collect(Collectors.joining(", ")));
        }
      case "notIn":
        if (value instanceof List) {
          log.warn("conditionType 'notIn' is require Set");
          throw new RuntimeException(
              "conditionType 'notIn' is require Set, yours: " + value.getClass());
        }
        final Set<?> values = (Set<?>) value;
        if (values.isEmpty()) {
          log.warn("WHERE - empty in cause : {}", dbColumnName);
          throw new RuntimeException("WHERE - empty in cause : " + dbColumnName);
        }
        return MessageFormat.format(
            "`{0}` NOT IN ({1})",
            dbColumnName,
            values.stream().map(this::getFormattedValue).collect(Collectors.joining(", ")));
      case "null":
        return MessageFormat.format("`{0}` IS NULL", dbColumnName);
      case "notNull":
        return MessageFormat.format("`{0}` IS NOT NULL", dbColumnName);
      case "contains":
        return MessageFormat.format(
            "INSTR(`{0}`, {1}) > 0", dbColumnName, this.getFormattedValue(value));
      case "notContains":
        return MessageFormat.format(
            "INSTR(`{0}`, {1}) = 0", dbColumnName, this.getFormattedValue(value));
      case "startsWith":
        return MessageFormat.format(
            "INSTR(`{0}`, {1}) = 1", dbColumnName, this.getFormattedValue(value));
      case "endsWith":
        return MessageFormat.format(
            "RIGHT(`{0}`, CHAR_LENGTH({1})) = {1}", dbColumnName, this.getFormattedValue(value));
      case "lt":
        return MessageFormat.format("`{0}` < {1}", dbColumnName, this.getFormattedValue(value));
      case "lte":
        return MessageFormat.format("`{0}` <= {1}", dbColumnName, this.getFormattedValue(value));
      case "gt":
        return MessageFormat.format("`{0}` > {1}", dbColumnName, this.getFormattedValue(value));
      case "gte":
        return MessageFormat.format("`{0}` >= {1}", dbColumnName, this.getFormattedValue(value));
      case "eq":
      default:
        return this.getEqualSql(dbColumnName, value);
    }
  }

  private String getEqualSql(final String dbColumnName, final Object value) {
    return MessageFormat.format("`{0}` = {1}", dbColumnName, this.getFormattedValue(value));
  }

  private String getFormattedValue(final Object value) {
    switch (value) {
      case null -> {
        return "null";
      }
      case String str -> {
        if (this.isISO8601String(str)) {
          return "'"
              + converterInstantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS")
              + "'";
          // FIXME: MYSQL 사용시 주석을 풀어 위에 코드를 대체해주세요.
          //        return MessageFormat.format(
          //            "FROM_UNIXTIME({0,number,#})",
          //            Integer.parseInt(String.valueOf(Instant.parse(str).toEpochMilli() / 1000)));
        } else {
          return "'" + str.replaceAll("'", "''") + "'";
        }
      }
      case Instant instant -> {
        return "'" + converterInstantToString(instant, "yyyy-MM-dd HH:mm:ss.SSS") + "'";
        // FIXME: MYSQL 사용시 주석을 풀어 위에 코드를 대체해주세요.
        //      MYSQL
        //      return MessageFormat.format(
        //          "FROM_UNIXTIME({0,number,#})",
        //          Integer.parseInt(String.valueOf((instant).toEpochMilli() / 1000)));
      }
      case Enum<?> enum1 -> {
        if (enum1 instanceof ValueEnum valueEnum) {
          return "'" + valueEnum.getValue() + "'";
        }
        return "'" + enum1.name() + "'";
      }
      case List<?> list -> {
        return "'["
            + list.stream()
                .map(v -> getFormattedValue(v).replaceAll("'", "\""))
                .collect(Collectors.joining(", "))
            + "]'";
      }
      case Set<?> set -> {
        return "'["
            + set.stream().map(this::getFormattedValue).collect(Collectors.joining(", "))
            + "]'";
      }
      case Map<?, ?> map -> {
        StringBuilder sb = new StringBuilder();
        map.forEach(
            (k, v) -> {
              if (!sb.isEmpty()) {
                sb.append(", ");
              }
              sb.append("\"").append(k).append("\":").append(getFormattedValue(v));
            });
        return "\"{" + sb + "}\"";
      }
      default -> {
        return value.toString().replaceAll("'", "''");
      }
    }
  }

  private void getWhereSql(final SQL sql, final Map<String, Object> whereConditions) {
    whereConditions.forEach(
        (key, value) -> {
          final String columnName = StringUtils.substringBefore(key, ":");
          final String conditionType =
              StringUtils.defaultString(StringUtils.substringAfter(key, ":"), "eq");
          sql.WHERE(this.getWhereString(conditionType, getCamelCaseToSnakeCase(columnName), value));
        });
  }

  private String wrapIdentifier(final String identifier) {
    return "`" + identifier + "`";
  }

  private boolean isISO8601String(final String value) {
    return StringUtils.countMatches(value, '-') == 2
        && StringUtils.countMatches(value, ':') == 2
        && StringUtils.countMatches(value, 'T') == 1
        && (StringUtils.endsWith(value, "Z") || StringUtils.countMatches(value, '+') == 1);
  }

  private static String getCamelCaseToSnakeCase(final String str) {
    final StringBuilder result = new StringBuilder(str.length() * 2);
    result.append(Character.toLowerCase(str.charAt(0)));
    for (int i = 1; i < str.length(); i++) {
      final char ch = str.charAt(i);
      if (Character.isUpperCase(ch)) {
        result.append('_').append(Character.toLowerCase(ch));
      } else {
        result.append(ch);
      }
    }
    return result.toString();
  }

  public static Map<String, Object> toMap(final Object source) {
    final Map<String, Object> map = new HashMap<>();
    final Field[] fields = getAllFields(source.getClass());
    for (final Field field : fields) {
      field.setAccessible(true);
      try {
        map.put(field.getName(), field.get(source));
      } catch (final Exception e) {
        log.warn(ExceptionUtils.getStackTrace(e));
      }
    }
    return map;
  }

  private String converterInstantToString(final Instant instant, final String pattern) {
    return OffsetDateTime.ofInstant(instant, ZoneId.of("UTC"))
        .format(DateTimeFormatter.ofPattern(pattern));
  }

  private static Field[] getAllFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();

    // 현재 클래스부터 시작해서 모든 상위 클래스를 순회
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
      currentClass = currentClass.getSuperclass();
    }

    return fields.toArray(new Field[0]);
  }
}
