package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import java.text.SimpleDateFormat;
import java.time.*;
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

  /** WHERE 조건이 null 또는 비어 있으면 예외 처리 */
  protected void validateWhereConditions(final Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      log.warn("whereConditions is empty");
      throw new MybatisRepositoryException("'where' Conditions is required");
    }
  }

  /** 주어진 whereConditions를 순회하며, SQL에 WHERE 절을 추가한다. */
  protected void buildWhereClause(final SQL sql, final Map<String, Object> whereConditions) {
    if (whereConditions == null) {
      return;
    }

    for (Map.Entry<String, Object> entry : whereConditions.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      // key를 ":" 기준으로 앞뒤로 잘라서 column/conditionType 구분
      String columnName = stringHelper.substringBefore(key);
      String conditionType = stringHelper.substringAfter(key);
      if (conditionType.isEmpty()) {
        conditionType = "eq"; // 기본 eq
      }

      String dbColumnName = entityHelper.getColumnName(columnName);
      String clause = buildConditionClause(conditionType, dbColumnName, value);
      sql.WHERE(clause);
    }
  }

  /** conditionType(=, <>, IN, …)을 처리하고 실제 SQL 조건절을 반환 */
  private String buildConditionClause(
      final String conditionType, final String dbColumnName, final Object value) {
    Condition condition = Condition.from(conditionType);
    return condition.buildClause(dbColumnName, value, this);
  }

  /** SELECT 절을 구성한다. distinctColumns와 targetColumns 모두 비어 있으면 entity의 모든 필드를 SELECT. */
  protected void appendSelectColumns(
      SQL sql, Set<String> distinctColumns, Set<String> targetColumns) {
    // 둘 다 비었으면, 전체 필드 SELECT
    if (distinctColumns.isEmpty() && targetColumns.isEmpty()) {
      for (String field : entityHelper.getEntityFields()) {
        sql.SELECT(stringHelper.wrapIdentifier(entityHelper.getColumnName(field)));
      }
      return;
    }

    // DISTINCT 컬럼
    for (String distinctCol : distinctColumns) {
      sql.SELECT_DISTINCT(stringHelper.wrapIdentifier(entityHelper.getColumnName(distinctCol)));
    }

    // 일반 컬럼
    for (String targetCol : targetColumns) {
      if (!distinctColumns.contains(targetCol)) {
        sql.SELECT(stringHelper.wrapIdentifier(entityHelper.getColumnName(targetCol)));
      }
    }
  }

  /** ORDER BY 절 구성 */
  protected void appendOrderBy(SQL sql, List<String> orderByConditions) {
    if (orderByConditions == null) {
      return;
    }
    for (String condition : orderByConditions) {
      if (condition.startsWith("-")) {
        String realCol = condition.substring(1);
        sql.ORDER_BY(stringHelper.wrapIdentifier(entityHelper.getColumnName(realCol)) + " DESC");
      } else {
        sql.ORDER_BY(stringHelper.wrapIdentifier(entityHelper.getColumnName(condition)) + " ASC");
      }
    }
  }

  /** WHERE 절 존재 여부 확인 (UPDATE, DELETE 시 강제 사용) */
  protected void ensureWhereClause(final SQL sql) {
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
  }

  /** IN, NOT IN 절 빌드 */
  protected String buildInClause(String dbColumnName, Object value, boolean isNotIn) {
    if (!(value instanceof Set)) {
      log.warn("conditionType '{}' requires Set", (isNotIn ? "notIn" : "in"));
      throw new MybatisRepositoryException(
          String.format(
              "conditionType '%s' requires Set, yours: %s",
              (isNotIn ? "notIn" : "in"), value == null ? null : value.getClass()));
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

  /** '=' 조건절 빌드 */
  protected String buildEqualClause(final String dbColumnName, final Object value) {
    return String.format("`%s` = %s", dbColumnName, formatValueForSQL(value));
  }

  /** SQL에서 사용할 수 있도록 값 포맷팅 */
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
    // 숫자, Boolean 등등은 기본 toString(); 문자열 내 단일인용부호 이스케이프
    return stringHelper.escapeSingleQuote(value.toString());
  }

  private String formatStringValue(String str) {
    // ISO8601이면 Instant로 변환
    if (stringHelper.isISO8601String(str)) {
      return "'"
          + stringHelper.instantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS")
          + "'";
    }
    return "'" + stringHelper.escapeSingleQuote(str) + "'";
  }

  private String formatEnumValue(Enum<?> enumValue) {
    if (enumValue instanceof io.github.bestheroz.mybatis.type.ValueEnum) {
      return "'" + ((ValueEnum) enumValue).getValue() + "'";
    }
    return "'" + enumValue.name() + "'";
  }

  private String formatCollectionValue(Collection<?> collection) {
    // '[val1, val2, val3]'
    String joined =
        collection.stream()
            .map(v -> formatValueForSQL(v).replace("'", "\"")) // 내부 문자열은 " 로 치환
            .collect(Collectors.joining(", "));
    return "'[" + joined + "]'";
  }

  private String formatMapValue(Map<?, ?> map) {
    // JSON 비슷하게: "{\"key1\":val1, \"key2\":val2, ...}"
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
