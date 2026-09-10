package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.ibatis.jdbc.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MybatisClauseBuilder {
  private static final Logger log = LoggerFactory.getLogger(MybatisClauseBuilder.class);

  // 상수 정의
  private static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
  private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
  private static final String DEFAULT_TIME_FORMAT = "HH:mm:ss";

  // 설정 가능한 값들을 위한 Properties 참조
  private final MybatisRepositoryProperties properties;

  // 스레드 안전한 DateTimeFormatter 사용
  private static final DateTimeFormatter DATETIME_FORMATTER =
      DateTimeFormatter.ofPattern(DEFAULT_DATETIME_FORMAT);
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT);

  private final MybatisStringHelper stringHelper;
  private final MybatisEntityHelper entityHelper;

  public MybatisClauseBuilder(MybatisStringHelper stringHelper, MybatisEntityHelper entityHelper) {
    this.stringHelper = stringHelper;
    this.entityHelper = entityHelper;
    this.properties = MybatisRepositoryProperties.getInstance();
  }

  public MybatisClauseBuilder(
      MybatisStringHelper stringHelper,
      MybatisEntityHelper entityHelper,
      MybatisRepositoryProperties properties) {
    this.stringHelper = stringHelper;
    this.entityHelper = entityHelper;
    this.properties = properties != null ? properties : MybatisRepositoryProperties.getInstance();
  }

  /**
   * 주어진 whereConditions를 순회하며, SQL에 WHERE 절을 추가한다.
   *
   * @param sql MyBatis SQL 빌더
   * @param whereConditions 키: 필드명(:조건타입), 값: 필터 값
   * @param entityClass 엔티티 클래스 (예: User.class)
   */
  protected void buildWhereClause(
      final SQL sql, final Map<String, Object> whereConditions, final Class<?> entityClass) {
    if (whereConditions == null) {
      return;
    }

    Map<String, Object> extractedWhereConditions = extractWhereConditions(whereConditions);

    for (Map.Entry<String, Object> entry : extractedWhereConditions.entrySet()) {
      final String key = entry.getKey();
      final Object value = entry.getValue();

      // key를 ":" 기준으로 앞뒤로 잘라서 column/conditionType 구분
      String columnName = stringHelper.substringBefore(key);
      String conditionType = stringHelper.substringAfter(key);
      if (conditionType.isEmpty()) {
        conditionType = "eq"; // 기본 eq
      }

      // DB Column (entityClass를 함께 넘김)
      String dbColumnName = entityHelper.getColumnName(entityClass, columnName);

      // Condition 선택 후 빌드
      sql.WHERE(Condition.from(conditionType).buildClause(dbColumnName, value, this));
    }
  }

  private Map<String, Object> extractWhereConditions(Map<String, Object> params) {
    Object whereConditions = params.get("whereConditions");
    if (whereConditions instanceof Map) {
      return (Map<String, Object>) whereConditions;
    }
    return params;
  }

  /**
   * SELECT 절을 구성한다. distinctColumns와 targetColumns 모두 비어 있으면 entity의 모든 필드를 SELECT.
   *
   * @param sql MyBatis SQL 빌더
   * @param distinctColumns DISTINCT 처리할 필드명 집합 (자바 필드명)
   * @param targetColumns 실제 조회할 필드명 집합 (자바 필드명)
   * @param entityClass 엔티티 클래스 (예: User.class)
   */
  protected void appendSelectColumns(
      final SQL sql,
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Class<?> entityClass) {

    // 둘 다 비었으면, 전체 필드 SELECT
    if ((distinctColumns == null || distinctColumns.isEmpty())
        && (targetColumns == null || targetColumns.isEmpty())) {
      for (String field : entityHelper.getEntityFields(entityClass)) {
        String colName = entityHelper.getColumnName(entityClass, field);
        sql.SELECT(stringHelper.wrapIdentifier(colName));
      }
      return;
    }

    // DISTINCT 컬럼
    if (distinctColumns != null) {
      for (String distinctCol : distinctColumns) {
        sql.SELECT_DISTINCT(
            stringHelper.wrapIdentifier(entityHelper.getColumnName(entityClass, distinctCol)));
      }
    }

    // 일반 컬럼
    if (targetColumns != null) {
      for (String targetCol : targetColumns) {
        if (distinctColumns == null || !distinctColumns.contains(targetCol)) {
          sql.SELECT(
              stringHelper.wrapIdentifier(entityHelper.getColumnName(entityClass, targetCol)));
        }
      }
    }
  }

  /**
   * ORDER BY 절 구성
   *
   * @param sql MyBatis SQL 빌더
   * @param orderByConditions 정렬 조건 리스트 (예: ["-createdAt", "name"])
   * @param entityClass 엔티티 클래스 (예: User.class)
   */
  protected void appendOrderBy(
      final SQL sql, final List<String> orderByConditions, final Class<?> entityClass) {
    if (orderByConditions == null) {
      return;
    }
    for (String condition : orderByConditions) {
      if (condition.startsWith("-")) {
        String realCol = condition.substring(1);
        sql.ORDER_BY(
            stringHelper.wrapIdentifier(entityHelper.getColumnName(entityClass, realCol))
                + " DESC");
      } else {
        sql.ORDER_BY(
            stringHelper.wrapIdentifier(entityHelper.getColumnName(entityClass, condition))
                + " ASC");
      }
    }
  }

  /** WHERE 절 존재 여부 확인 (UPDATE, DELETE 시 강제 사용) */
  protected void ensureWhereClause(final SQL sql) {
    ensureWhereClause(sql.toString());
  }

  /**
   * 이미 문자열로 만들어 둔 SQL 을 그대로 검사한다.
   *
   * <p>예전에는 SQL 을 한 번 더 만들고 {@code toLowerCase()} 로 전체 사본을 또 떴다. 큰 UPDATE 문에서는 그 두 벌이 그대로 낭비다.
   */
  protected void ensureWhereClause(final String renderedSql) {
    if (!containsIgnoreCase(renderedSql, "where ")) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
  }

  private static boolean containsIgnoreCase(final String haystack, final String needle) {
    final int last = haystack.length() - needle.length();
    for (int i = 0; i <= last; i++) {
      if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
        return true;
      }
    }
    return false;
  }

  // ===========================================
  // Condition별 빌드 메서드
  // ===========================================
  protected String buildInClause(
      final String dbColumnName, final Object value, final boolean isNotIn) {
    if (!(value instanceof Set)) {
      log.warn("conditionType '{}' requires Set", (isNotIn ? "notIn" : "in"));
      throw new MybatisRepositoryException(
          String.format(
              "conditionType '%s' requires Set, yours: %s",
              (isNotIn ? "notIn" : "in"), value == null ? null : value.getClass()));
    }

    @SuppressWarnings("unchecked")
    Set<Object> inValues = (Set<Object>) value;
    if (inValues.isEmpty()) {
      log.warn("WHERE - empty in clause : {}", dbColumnName);
      throw new MybatisRepositoryException("WHERE - empty in clause : " + dbColumnName);
    }

    if (inValues.size() > properties.getMaxInClauseSize()) {
      throw new MybatisRepositoryException(
          "IN clause size exceeds maximum limit: "
              + properties.getMaxInClauseSize()
              + ", actual: "
              + inValues.size());
    }

    // String.format 은 포맷 문자열을 매번 파싱하고 Formatter 를 새로 만든다. 조건 하나마다 거치는 자리다.
    // (isNotIn 이 false 일 때 공백이 둘인 것은 기존 출력 그대로 유지한 것이다.)
    final StringBuilder sb = new StringBuilder(dbColumnName.length() + inValues.size() * 8 + 16);
    sb.append('`').append(dbColumnName).append("` ").append(isNotIn ? "NOT" : "").append(" IN (");
    boolean first = true;
    for (Object inValue : inValues) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(formatValueForSQL(inValue));
    }
    return sb.append(')').toString();
  }

  protected String buildEqualClause(final String dbColumnName, final Object value) {
    return "`" + dbColumnName + "` = " + formatValueForSQL(value);
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
      return "'" + stringHelper.instantToString((Instant) value, DEFAULT_DATETIME_FORMAT) + "'";
    } else if (value instanceof java.sql.Date) {
      // JDBC 의 "날짜만" 타입. java.util.Date 의 하위 타입이지만 toInstant() 가
      // UnsupportedOperationException 을 던지므로 아래 Date 분기보다 먼저 걸러야 한다.
      // toLocalDate() 는 mybatis-repository.timezone 이 아니라 JVM 기본 타임존으로 읽는데, 이건 의도한
      // 것이다 -- 이 타입의 값은 드라이버나 Date.valueOf(LocalDate) 가 이미 JVM 기본 타임존 자정으로
      // 정규화해 넣은 것이라, 다른 타임존으로 다시 해석하면 날짜가 하루씩 밀린다.
      return "'" + ((java.sql.Date) value).toLocalDate().format(DATE_FORMATTER) + "'";
    } else if (value instanceof java.sql.Time) {
      // JDBC 의 "시각만" 타입. 위와 같은 이유로 먼저 거르고, 같은 이유로 JVM 기본 타임존으로 읽는다.
      // 이 타입에는 초 미만이 없어 HH:mm:ss 로 충분하다.
      return "'" + ((java.sql.Time) value).toLocalTime().format(TIME_FORMATTER) + "'";
    } else if (value instanceof Date) {
      return "'"
          + ((Date) value).toInstant().atZone(properties.getDateZoneId()).format(DATETIME_FORMATTER)
          + "'";
    } else if (value instanceof LocalDateTime) {
      return "'" + ((LocalDateTime) value).format(DATETIME_FORMATTER) + "'";
    } else if (value instanceof LocalDate) {
      return "'" + ((LocalDate) value).format(DATE_FORMATTER) + "'";
    } else if (value instanceof OffsetDateTime) {
      return "'"
          + stringHelper.instantToString(
              ((OffsetDateTime) value).toInstant(), DEFAULT_DATETIME_FORMAT)
          + "'";
    } else if (value instanceof ZonedDateTime) {
      // 없으면 toString() 으로 떨어져 '2025-01-02T12:34:56+09:00[Asia/Seoul]' 이 그대로 SQL 에 들어간다.
      return "'"
          + stringHelper.instantToString(
              ((ZonedDateTime) value).toInstant(), DEFAULT_DATETIME_FORMAT)
          + "'";
    } else if (value instanceof OffsetTime) {
      // 없으면 toString() 으로 떨어져 '12:34:56+09:00' 이 그대로 들어간다. 날짜가 없으면 어느 날의
      // 오프셋인지 알 수 없어 옮길 기준이 없으므로, 오프셋만 떼고 벽시계를 찍는다.
      return "'" + ((OffsetTime) value).toLocalTime().format(TIME_FORMATTER) + "'";
    } else if (value instanceof LocalTime) {
      // toString() 은 초가 0 이면 'HH:mm' 으로 줄여 버린다. 자리수를 고정한다.
      return "'" + ((LocalTime) value).format(TIME_FORMATTER) + "'";
    } else if (value instanceof Enum) {
      return formatEnumValue((Enum<?>) value);
    } else if (value instanceof Collection) {
      return formatCollectionValue((Collection<?>) value);
    } else if (value instanceof Map) {
      return formatMapValue((Map<?, ?>) value);
    } else if (value instanceof Number || value instanceof Boolean) {
      // 숫자와 Boolean은 안전하게 처리
      return value.toString();
    }
    // 기타 객체는 문자열로 변환 후 이스케이프
    String stringValue = value.toString();
    if (stringValue.length() > properties.getMaxStringValueLength()) {
      throw new MybatisRepositoryException(
          "Value too long for SQL: "
              + stringValue.length()
              + ", max allowed: "
              + properties.getMaxStringValueLength());
    }
    return "'" + stringHelper.escapeSingleQuote(stringValue) + "'";
  }

  private String formatStringValue(final String str) {
    // ISO8601이면 Instant로 변환
    if (stringHelper.isISO8601String(str)) {
      final Instant instant = stringHelper.parseIso8601(str);
      if (instant != null) {
        return "'" + stringHelper.instantToString(instant, DEFAULT_DATETIME_FORMAT) + "'";
      }
      // 모양만 닮았을 뿐 시각이 아니면 예외로 질의를 깨뜨리지 않고 평범한 문자열로 떨어뜨린다
    }
    // 일반 문자열
    return "'" + stringHelper.escapeSingleQuote(str) + "'";
  }

  private String formatEnumValue(final Enum<?> enumValue) {
    if (enumValue instanceof ValueEnum) {
      ValueEnum ve = (ValueEnum) enumValue;
      // getValue() 는 구현하는 쪽이 정하는 임의의 문자열이라 다른 값과 똑같이 이스케이프를 거쳐야 한다.
      return "'" + stringHelper.escapeSingleQuote(ve.getValue()) + "'";
    }
    // 기본 name() (자바 식별자라 바뀔 문자는 없지만 경로를 하나로 맞춘다)
    return "'" + stringHelper.escapeSingleQuote(enumValue.name()) + "'";
  }

  private String formatCollectionValue(final Collection<?> collection) {
    // 예: '[val1, val2, val3]' 형태
    String joined =
        collection.stream()
            .map(v -> formatValueForSQL(v).replace('\'', '"'))
            .collect(Collectors.joining(", "));
    return "'[" + joined + "]'";
  }

  private String formatMapValue(final Map<?, ?> map) {
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

      Object val = entry.getValue();
      if (val instanceof String) {
        sb.append("\"").append(stringHelper.escapeSingleQuote(String.valueOf(val))).append("\"");
      } else {
        sb.append(formatValueForSQL(val));
      }
    }
    sb.append("}\"");
    return sb.toString();
  }
}
