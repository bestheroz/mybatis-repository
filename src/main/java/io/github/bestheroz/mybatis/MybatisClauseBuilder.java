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

public class MybatisClauseBuilder {
  private static final Logger log = LoggerFactory.getLogger(MybatisClauseBuilder.class);

  private final MybatisStringHelper stringHelper;
  private final MybatisEntityHelper entityHelper;

  public MybatisClauseBuilder(MybatisStringHelper stringHelper, MybatisEntityHelper entityHelper) {
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
      final String key = entry.getKey();
      final Object value = entry.getValue();

      // key를 ":" 기준으로 앞뒤로 잘라서 column/conditionType 구분
      String columnName = stringHelper.substringBefore(key);
      String conditionType = stringHelper.substringAfter(key);
      if (conditionType.isEmpty()) {
        conditionType = "eq"; // 기본 eq
      }

      // DB Column
      String dbColumnName = entityHelper.getColumnName(columnName);

      // Condition 선택 후 빌드
      sql.WHERE(Condition.from(conditionType).buildClause(dbColumnName, value, this));
    }
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
    // 간단한 방법: toString().toLowerCase().contains("where ")
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
  }

  // ===========================================
  // Condition별 빌드 메서드
  // ===========================================
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

  protected String buildEqualClause(final String dbColumnName, final Object value) {
    return String.format("`%s` = %s", dbColumnName, formatValueForSQL(value));
  }

  // ===========================================
  // Value Formatting
  // ===========================================
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
    } else if (value instanceof Enum) {
      return formatEnumValue((Enum<?>) value);
    } else if (value instanceof Collection) {
      return formatCollectionValue((Collection<?>) value);
    } else if (value instanceof Map) {
      return formatMapValue((Map<?, ?>) value);
    }
    // 숫자, Boolean 등등 기본 toString(); 단, 문자열 내 단일인용부호 이스케이프
    return stringHelper.escapeSingleQuote(value.toString());
  }

  private String formatStringValue(String str) {
    // ISO8601이면 Instant로 변환
    if (stringHelper.isISO8601String(str)) {
      return "'"
          + stringHelper.instantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS")
          + "'";
    }
    // 일반 문자열
    return "'" + stringHelper.escapeSingleQuote(str) + "'";
  }

  private String formatEnumValue(Enum<?> enumValue) {
    if (enumValue instanceof ValueEnum) {
      // ValueEnum 처리
      ValueEnum ve = (ValueEnum) enumValue;
      return "'" + ve.getValue() + "'";
    }
    // 기본 name()
    return "'" + enumValue.name() + "'";
  }

  private String formatCollectionValue(Collection<?> collection) {
    // 예: '[val1, val2, val3]' 형태
    String joined =
        collection.stream()
            .map(v -> formatValueForSQL(v).replace("'", "\"")) // 내부 문자열은 " 로 치환
            .collect(Collectors.joining(", "));
    return "'[" + joined + "]'";
  }

  private String formatMapValue(Map<?, ?> map) {
    // 예: "{\"key1\":val1, \"key2\":val2, ...}"
    StringBuilder sb = new StringBuilder().append("\"{");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append("\"")
          .append(stringHelper.escapeSingleQuote(String.valueOf(entry.getKey())))
          .append("\":");

      Object value = entry.getValue();
      if (value instanceof String) {
        // 문자열 값인 경우 formatValueForSQL() 호출 없이 직접 큰따옴표 처리
        sb.append("\"").append(stringHelper.escapeSingleQuote(String.valueOf(value))).append("\"");
      } else {
        sb.append(formatValueForSQL(value));
      }
    }
    sb.append("}\"");
    return sb.toString();
  }
}
