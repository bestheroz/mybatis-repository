package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.ibatis.jdbc.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MybatisClauseBuilderHelper {
  private static final Logger log = LoggerFactory.getLogger(MybatisClauseBuilderHelper.class);

  private final MybatisStringHelper stringHelper;
  private final MybatisEntityHelper entityHelper;

  public MybatisClauseBuilderHelper(
      MybatisStringHelper stringHelper, MybatisEntityHelper entityHelper) {
    this.stringHelper = stringHelper;
    this.entityHelper = entityHelper;
  }

  protected void validateWhereConditions(final Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      log.warn("whereConditions is empty");
      throw new MybatisRepositoryException("'where' Conditions is required");
    }
  }

  protected void buildWhereClause(final SQL sql, final Map<String, Object> whereConditions) {
    if (whereConditions == null) {
      return;
    }

    for (Map.Entry<String, Object> entry : whereConditions.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      String columnName = stringHelper.substringBefore(key);
      String conditionType = stringHelper.substringAfter(key);
      if (conditionType.isEmpty()) {
        conditionType = "eq"; // 기본 eq
      }

      String dbColumnName = entityHelper.getColumnName(columnName);
      sql.WHERE(buildConditionClause(conditionType, dbColumnName, value));
    }
  }

  private String buildConditionClause(
      final String conditionType, final String dbColumnName, final Object value) {
    switch (conditionType) {
      case "ne":
      case "not":
        return String.format("`%s` <> %s", dbColumnName, formatValueForSQL(value));
      case "in":
        return buildInClause(dbColumnName, value, false);
      case "notIn":
        return buildInClause(dbColumnName, value, true);
      case "null":
        return String.format("`%s` IS NULL", dbColumnName);
      case "notNull":
        return String.format("`%s` IS NOT NULL", dbColumnName);
      case "contains":
        return String.format("INSTR(`%s`, %s) > 0", dbColumnName, formatValueForSQL(value));
      case "notContains":
        return String.format("INSTR(`%s`, %s) = 0", dbColumnName, formatValueForSQL(value));
      case "startsWith":
        return String.format("INSTR(`%s`, %s) = 1", dbColumnName, formatValueForSQL(value));
      case "endsWith":
        return String.format(
            "RIGHT(`%s`, CHAR_LENGTH(%s)) = %s",
            dbColumnName, formatValueForSQL(value), formatValueForSQL(value));
      case "lt":
        return String.format("`%s` < %s", dbColumnName, formatValueForSQL(value));
      case "lte":
        return String.format("`%s` <= %s", dbColumnName, formatValueForSQL(value));
      case "gt":
        return String.format("`%s` > %s", dbColumnName, formatValueForSQL(value));
      case "gte":
        return String.format("`%s` >= %s", dbColumnName, formatValueForSQL(value));
      case "eq":
      default:
        return buildEqualClause(dbColumnName, value);
    }
  }

  protected void appendOrderByClause(SQL sql, List<String> orderByConditions) {
    for (String condition : orderByConditions) {
      if (condition.startsWith("-")) {
        String realCol = condition.substring(1);
        sql.ORDER_BY(stringHelper.wrapIdentifier(entityHelper.getColumnName(realCol)) + " DESC");
      } else {
        sql.ORDER_BY(stringHelper.wrapIdentifier(entityHelper.getColumnName(condition)) + " ASC");
      }
    }
  }

  protected void requireWhereClause(final SQL sql) {
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
  }

  /** SELECT 구문을 구성하는 로직. distinctColumns, targetColumns 가 모두 비어 있으면 entity 의 모든 필드를 SELECT */
  protected void appendSelectClause(
      SQL sql, Set<String> distinctColumns, Set<String> targetColumns) {
    // distinctColumns, targetColumns 모두 비어 있으면, 전체 컬럼 SELECT
    if (distinctColumns.isEmpty() && targetColumns.isEmpty()) {
      for (String field : entityHelper.getEntityFields()) {
        sql.SELECT(stringHelper.wrapIdentifier(entityHelper.getColumnName(field)));
      }
      return;
    }

    // distinctColumns
    for (String distinctCol : distinctColumns) {
      sql.SELECT_DISTINCT(stringHelper.wrapIdentifier(entityHelper.getColumnName(distinctCol)));
    }

    // targetColumns
    for (String targetCol : targetColumns) {
      if (!distinctColumns.contains(targetCol)) {
        sql.SELECT(stringHelper.wrapIdentifier(entityHelper.getColumnName(targetCol)));
      }
    }
  }

  private String buildInClause(String dbColumnName, Object value, boolean isNotIn) {
    if (!(value instanceof Set)) {
      log.warn("conditionType '{}' requires Set", (isNotIn ? "notIn" : "in"));
      throw new MybatisRepositoryException(
          String.format(
              "conditionType '%s' requires Set, yours: %s",
              (isNotIn ? "notIn" : "in"), value.getClass()));
    }

    Set<?> inValues = (Set<?>) value;
    if (inValues.isEmpty()) {
      log.warn("WHERE - empty in clause : {}", dbColumnName);
      throw new MybatisRepositoryException("WHERE - empty in clause : " + dbColumnName);
    }

    String inFormatted =
        inValues.stream().map(this::formatValueForSQL).collect(Collectors.joining(", "));
    return String.format("`%s` %s IN (%s)", dbColumnName, (isNotIn ? "NOT" : ""), inFormatted);
  }

  protected String buildEqualClause(final String dbColumnName, final Object value) {
    return String.format("`%s` = %s", dbColumnName, formatValueForSQL(value));
  }

  protected String formatValueForSQL(final Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String) {
      return formatStringValue((String) value);
    } else if (value instanceof Instant) {
      return "'" + stringHelper.instantToString((Instant) value, "yyyy-MM-dd HH:mm:ss.SSS") + "'";
    } else if (value instanceof Date) {
      return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(value) + "'";
    } else if (value instanceof LocalDateTime) {
      return "'"
          + ((LocalDateTime) value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
          + "'";
    } else if (value instanceof LocalDate) {
      return "'" + ((LocalDate) value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "'";
    } else if (value instanceof OffsetDateTime) {
      return "'"
          + stringHelper.instantToString(
              ((OffsetDateTime) value).toInstant(), "yyyy-MM-dd HH:mm:ss.SSS")
          + "'";
    } else if (value instanceof Enum<?>) {
      return formatEnumValue((Enum<?>) value);
    } else if (value instanceof Collection<?>) {
      return formatCollectionValue((Collection<?>) value);
    } else if (value instanceof Map<?, ?>) {
      return formatMapValue((Map<?, ?>) value);
    }
    return stringHelper.escapeSingleQuote(value.toString());
  }

  private String formatStringValue(String str) {
    if (stringHelper.isISO8601String(str)) {
      return "'"
          + stringHelper.instantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS")
          + "'";
    }
    return "'" + stringHelper.escapeSingleQuote(str) + "'";
  }

  private String formatEnumValue(Enum<?> enumValue) {
    if (enumValue instanceof ValueEnum) {
      return "'" + ((ValueEnum) enumValue).getValue() + "'";
    }
    return "'" + enumValue.name() + "'";
  }

  private String formatCollectionValue(Collection<?> collection) {
    String joined =
        collection.stream()
            .map(v -> formatValueForSQL(v).replace("'", "\""))
            .collect(Collectors.joining(", "));
    return "'[" + joined + "]'";
  }

  private String formatMapValue(Map<?, ?> map) {
    StringBuilder sb = new StringBuilder().append("\"{");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append("\"")
          .append(stringHelper.escapeSingleQuote(String.valueOf(entry.getKey())))
          .append("\":")
          .append(formatValueForSQL(entry.getValue()));
    }
    sb.append("}\"");
    return sb.toString();
  }
}
