package com.github.bestheroz.mybatis;

import com.github.bestheroz.mybatis.type.ValueEnum;
import jakarta.persistence.Table;
import org.apache.ibatis.jdbc.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MybatisCommand {
  private static final Logger log = LoggerFactory.getLogger(MybatisCommand.class);

  public static final String SELECT_ITEMS = "getDistinctAndTargetItemsByMapOrderByLimitOffset";
  public static final String SELECT_ITEM_BY_MAP = "getItemByMap";
  public static final String COUNT_BY_MAP = "countByMap";
  public static final String INSERT = "insert";
  public static final String INSERT_BATCH = "insertBatch";
  public static final String UPDATE_MAP_BY_MAP = "updateMapByMap";
  public static final String DELETE_BY_MAP = "deleteByMap";

  public MybatisCommand() {}

  private static final Set<String> METHOD_LIST = Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList(
                  SELECT_ITEMS,
                  SELECT_ITEM_BY_MAP,
                  COUNT_BY_MAP,
                  INSERT,
                  INSERT_BATCH,
                  UPDATE_MAP_BY_MAP,
                  DELETE_BY_MAP
          ))
  );

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
    log.trace(sql.toString());
    return sql.toString();
  }

  private Class<?> getEntityClass() {
    for (StackTraceElement element : new Throwable().getStackTrace()) {
      if (isValidStackTraceElement(element)) {
        return getClassFromStackTraceElement(element);
      }
    }
    log.warn("stackTraceElements is Empty");
    throw new RuntimeException("stackTraceElements is required");
  }

  private boolean isValidStackTraceElement(StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      return METHOD_LIST.contains(element.getMethodName())
              && clazz.getInterfaces().length > 0
              && clazz.getInterfaces()[0].getGenericInterfaces().length > 0;
    } catch (ClassNotFoundException e) {
      log.warn(getStackTrace(e));
      return false;
    }
  }

  private Class<?> getClassFromStackTraceElement(StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      String genericInterface = clazz.getInterfaces()[0].getGenericInterfaces()[0].getTypeName();
      String className = substringBetween(genericInterface, "<", ">");
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      log.warn(getStackTrace(e));
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
      for (String field : this.getEntityFields()) {
        sql.SELECT(this.wrapIdentifier(getCamelCaseToSnakeCase(field)));
      }
    } else {
      for (String column : distinctColumns) {
        sql.SELECT_DISTINCT(this.wrapIdentifier(getCamelCaseToSnakeCase(column)));
      }
      for (String column : targetColumns) {
        if (!distinctColumns.contains(column)) {
          sql.SELECT(this.wrapIdentifier(getCamelCaseToSnakeCase(column)));
        }
      }
    }

    sql.FROM(this.getTableName());
    if (limit != null) {
      sql.LIMIT(limit);
    }
    if (offset != null) {
      sql.OFFSET(offset);
    }
    this.getWhereSql(sql, whereConditions);

    for (String condition : orderByConditions) {
      String column = getCamelCaseToSnakeCase(condition);
      if (column.startsWith("-")) {
        sql.ORDER_BY(this.wrapIdentifier(column.substring(1)) + " desc");
      } else {
        sql.ORDER_BY(this.wrapIdentifier(column));
      }
    }

    log.trace(sql.toString());
    return sql.toString();
  }

  public String getItemByMap(final Map<String, Object> whereConditions) {
    this.verifyWhereKey(whereConditions);
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            whereConditions,
            Collections.emptyList(),
            null,
            null
    );
  }

  public <T> String insert(@NonNull final T entity) {
    final SQL sql = new SQL();
    sql.INSERT_INTO(getTableName(entity.getClass()));
    Map<String, Object> entityMap = toMap(entity);
    for (Map.Entry<String, Object> entry : entityMap.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (!MybatisProperties.getExcludeFields().contains(key)) {
        sql.VALUES(
                this.wrapIdentifier(getCamelCaseToSnakeCase(key)),
                this.getFormattedValue(value)
        );
      }
    }

    log.trace(sql.toString());
    return sql.toString();
  }

  public <T> String insertBatch(@NonNull final List<T> entities) {
    if (entities.isEmpty()) {
      log.warn("entities are empty");
      throw new RuntimeException("entities empty");
    }
    final SQL sql = new SQL();
    String tableName = this.wrapIdentifier(getTableName(entities.get(0).getClass()));
    sql.INSERT_INTO(tableName);
    final Set<String> columns = this.getEntityFields(entities.get(0).getClass());

    String columnsJoined = columns.stream()
            .filter(item -> !MybatisProperties.getExcludeFields().contains(item))
            .map(str -> this.wrapIdentifier(getCamelCaseToSnakeCase(str)))
            .collect(Collectors.joining(", "));
    sql.INTO_COLUMNS(columnsJoined);

    List<List<String>> valuesList = new ArrayList<>();
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      List<String> values = new ArrayList<>();
      for (String column : columns) {
        String snakeCaseColumn = getCamelCaseToSnakeCase(column);
        Object value = entityMap.get(column);
        values.add(this.getFormattedValue(value));
      }
      valuesList.add(values);
    }

    String valuesJoined = valuesList.stream()
            .map(value -> String.join(", ", value))
            .collect(Collectors.joining("), ("));
    sql.INTO_VALUES(valuesJoined);

    log.trace(sql.toString());
    return sql.toString();
  }

  public String updateMapByMap(
          final Map<String, Object> updateMap,
          final Map<String, Object> whereConditions
  ) {
    this.verifyWhereKey(whereConditions);

    final SQL sql = new SQL();
    sql.UPDATE(this.getTableName());
    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      String javaFieldName = entry.getKey();
      Object value = entry.getValue();
      if (!MybatisProperties.getExcludeFields().contains(javaFieldName)) {
        String dbColumnName = getCamelCaseToSnakeCase(javaFieldName);
        sql.SET(this.getEqualSql(dbColumnName, value));
      }
    }

    this.getWhereSql(sql, whereConditions);
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new RuntimeException("whereConditions are empty");
    }
    log.trace(sql.toString());
    return sql.toString();
  }

  public String deleteByMap(final Map<String, Object> whereConditions) {
    this.verifyWhereKey(whereConditions);
    final SQL sql = new SQL();
    sql.DELETE_FROM(this.getTableName());
    this.getWhereSql(sql, whereConditions);
    this.requiredWhereConditions(sql);
    log.trace(sql.toString());
    return sql.toString();
  }

  private void requiredWhereConditions(final SQL sql) {
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new RuntimeException("whereConditions are required");
    }
  }

  private String getWhereString(
          final String conditionType,
          final String dbColumnName,
          final Object value
  ) {
    switch (conditionType) {
      case "ne":
      case "not":
        return String.format("`%s` <> %s", dbColumnName, this.getFormattedValue(value));
      case "in":
        if (!(value instanceof Set)) {
          log.warn("conditionType 'in' requires Set");
          throw new RuntimeException(
                  "conditionType 'in' requires Set, yours : " + value.getClass()
          );
        }
        Set<?> inValues = (Set<?>) value;
        if (inValues.isEmpty()) {
          log.warn("WHERE - empty in cause : {}", dbColumnName);
          throw new RuntimeException("WHERE - empty in cause : " + dbColumnName);
        }
        String inFormatted = inValues.stream()
                .map(this::getFormattedValue)
                .collect(Collectors.joining(", "));
        return String.format("`%s` IN (%s)", dbColumnName, inFormatted);
      case "notIn":
        if (!(value instanceof Set)) {
          log.warn("conditionType 'notIn' requires Set");
          throw new RuntimeException(
                  "conditionType 'notIn' requires Set, yours: " + value.getClass()
          );
        }
        Set<?> notInValues = (Set<?>) value;
        if (notInValues.isEmpty()) {
          log.warn("WHERE - empty in cause : {}", dbColumnName);
          throw new RuntimeException("WHERE - empty in cause : " + dbColumnName);
        }
        String notInFormatted = notInValues.stream()
                .map(this::getFormattedValue)
                .collect(Collectors.joining(", "));
        return String.format("`%s` NOT IN (%s)", dbColumnName, notInFormatted);
      case "null":
        return String.format("`%s` IS NULL", dbColumnName);
      case "notNull":
        return String.format("`%s` IS NOT NULL", dbColumnName);
      case "contains":
        return String.format("INSTR(`%s`, %s) > 0", dbColumnName, this.getFormattedValue(value));
      case "notContains":
        return String.format("INSTR(`%s`, %s) = 0", dbColumnName, this.getFormattedValue(value));
      case "startsWith":
        return String.format("INSTR(`%s`, %s) = 1", dbColumnName, this.getFormattedValue(value));
      case "endsWith":
        return String.format(
                "RIGHT(`%s`, CHAR_LENGTH(%s)) = %s",
                dbColumnName,
                this.getFormattedValue(value),
                this.getFormattedValue(value)
        );
      case "lt":
        return String.format("`%s` < %s", dbColumnName, this.getFormattedValue(value));
      case "lte":
        return String.format("`%s` <= %s", dbColumnName, this.getFormattedValue(value));
      case "gt":
        return String.format("`%s` > %s", dbColumnName, this.getFormattedValue(value));
      case "gte":
        return String.format("`%s` >= %s", dbColumnName, this.getFormattedValue(value));
      case "eq":
      default:
        return this.getEqualSql(dbColumnName, value);
    }
  }

  private String getEqualSql(final String dbColumnName, final Object value) {
    return String.format("`%s` = %s", dbColumnName, this.getFormattedValue(value));
  }

  private String getFormattedValue(final Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String) {
      String str = (String) value;
      if (isISO8601String(str)) {
        return "'" + converterInstantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS") + "'";
        // Uncomment for MySQL
        // return String.format("FROM_UNIXTIME(%d)", Instant.parse(str).toEpochMilli() / 1000);
      } else {
        return "'" + str.replace("'", "''") + "'";
      }
    }
    if (value instanceof Instant) {
      Instant instant = (Instant) value;
      return "'" + converterInstantToString(instant, "yyyy-MM-dd HH:mm:ss.SSS") + "'";
      // Uncomment for MySQL
      // return String.format("FROM_UNIXTIME(%d)", instant.toEpochMilli() / 1000);
    }
    if (value instanceof Enum<?>) {
      if (value instanceof ValueEnum) {
        return "'" + ((ValueEnum) value).getValue() + "'";
      }
      return "'" + ((Enum<?>) value).name() + "'";
    }
    if (value instanceof List<?>) {
      List<?> list = (List<?>) value;
      String listStr = list.stream()
              .map(v -> getFormattedValue(v).replace("'", "\""))
              .collect(Collectors.joining(", "));
      return "'[" + listStr + "]'";
    }
    if (value instanceof Set<?>) {
      Set<?> set = (Set<?>) value;
      String setStr = set.stream()
              .map(this::getFormattedValue)
              .collect(Collectors.joining(", "));
      return "'[" + setStr + "]'";
    }
    if (value instanceof Map<?, ?>) {
      Map<?, ?> map = (Map<?, ?>) value;
      StringBuilder sb = new StringBuilder();
      sb.append("\"{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append("\"").append(entry.getKey()).append("\":")
                .append(getFormattedValue(entry.getValue()));
      }
      sb.append("}\"");
      return sb.toString();
    }
    // Default case
    return value.toString().replace("'", "''");
  }

  private void getWhereSql(final SQL sql, final Map<String, Object> whereConditions) {
    for (Map.Entry<String, Object> entry : whereConditions.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      String columnName = substringBefore(key, ":");
      String conditionType = substringAfter(key, ":");
      if (conditionType.isEmpty()) {
        conditionType = "eq";
      }
      String whereClause = getWhereString(conditionType, getCamelCaseToSnakeCase(columnName), value);
      sql.WHERE(whereClause);
    }
  }

  private String wrapIdentifier(final String identifier) {
    return "`" + identifier + "`";
  }

  private boolean isISO8601String(final String value) {
    int countDash = 0;
    int countColon = 0;
    int countT = 0;
    int countPlus = 0;
    for (char c : value.toCharArray()) {
      if (c == '-') countDash++;
      if (c == ':') countColon++;
      if (c == 'T') countT++;
      if (c == '+') countPlus++;
    }
    return countDash == 2
            && countColon == 2
            && countT == 1
            && (value.endsWith("Z") || countPlus == 1);
  }

  private static String getCamelCaseToSnakeCase(final String str) {
    StringBuilder result = new StringBuilder(str.length() * 2);
    result.append(Character.toLowerCase(str.charAt(0)));
    for (int i = 1; i < str.length(); i++) {
      char ch = str.charAt(i);
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
        log.warn(getStackTrace(e));
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

    // Start with the current class and traverse all superclasses
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
      currentClass = currentClass.getSuperclass();
    }

    return fields.toArray(new Field[0]);
  }

  // Helper methods to replace StringUtils

  private static String substringBetween(String str, String open, String close) {
    if (str == null || open == null || close == null) {
      return null;
    }
    int start = str.indexOf(open);
    if (start != -1) {
      start += open.length();
      int end = str.indexOf(close, start);
      if (end != -1) {
        return str.substring(start, end);
      }
    }
    return null;
  }

  private static String substringBefore(String str, String separator) {
    if (str == null || separator == null) {
      return str;
    }
    int pos = str.indexOf(separator);
    if (pos == -1) {
      return str;
    }
    return str.substring(0, pos);
  }

  private static String substringAfter(String str, String separator) {
    if (str == null || separator == null) {
      return "";
    }
    int pos = str.indexOf(separator);
    if (pos == -1) {
      return "";
    }
    return str.substring(pos + separator.length());
  }

  private static String defaultString(String str, String defaultStr) {
    return str == null ? defaultStr : str;
  }

  private static String getStackTrace(Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }
}
