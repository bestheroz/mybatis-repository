package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
  protected int buildWhereClause(
      final SQL sql, final Map<String, Object> whereConditions, final Class<?> entityClass) {
    if (whereConditions == null) {
      return 0;
    }

    Map<String, Object> extractedWhereConditions = extractWhereConditions(whereConditions);

    int appended = 0;
    for (Map.Entry<String, Object> entry : extractedWhereConditions.entrySet()) {
      final String key = entry.getKey();
      final Object value = entry.getValue();

      // key를 ":" 기준으로 앞뒤로 잘라서 column/conditionType 구분
      String columnName = stringHelper.substringBefore(key);
      String conditionType = stringHelper.substringAfter(key);
      // 접미사 없는 키가 대부분이다. "eq" 를 넣고 다시 찾으면 조건마다 조회표를 한 번 뒤지게 된다.
      final Condition condition =
          conditionType.isEmpty() ? Condition.EQ : Condition.from(conditionType);

      // DB Column (entityClass를 함께 넘김)
      String dbColumnName = entityHelper.getColumnName(entityClass, columnName);

      // Condition 선택 후 빌드
      sql.WHERE(condition.buildClause(dbColumnName, value, this));
      appended++;
    }
    return appended;
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
        sql.SELECT(entityHelper.getWrappedColumnName(entityClass, field));
      }
      return;
    }

    // DISTINCT 컬럼
    if (distinctColumns != null) {
      for (String distinctCol : distinctColumns) {
        sql.SELECT_DISTINCT(entityHelper.getWrappedColumnName(entityClass, distinctCol));
      }
    }

    // 일반 컬럼
    if (targetColumns != null) {
      for (String targetCol : targetColumns) {
        if (distinctColumns == null || !distinctColumns.contains(targetCol)) {
          sql.SELECT(entityHelper.getWrappedColumnName(entityClass, targetCol));
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
        sql.ORDER_BY(entityHelper.getWrappedColumnName(entityClass, realCol) + " DESC");
      } else {
        sql.ORDER_BY(entityHelper.getWrappedColumnName(entityClass, condition) + " ASC");
      }
    }
  }

  /**
   * WHERE 절 존재 여부 확인 (UPDATE, DELETE 시 강제 사용).
   *
   * <p>{@link #buildWhereClause} 가 실제로 붙인 조건 개수로 판정한다. 예전에는 완성된 SQL 문자열에서 {@code "where "} 를 찾았는데,
   * 그러면 WHERE 절이 아니라 SET 절의 값 리터럴에도 걸린다 -- {@code SET `memo` = 'delivered somewhere else'} 한 줄이면
   * WHERE 없는 UPDATE 가 이 가드를 그대로 통과해 전 행을 갱신했다. 앞단의 {@code whereConditions.isEmpty()} 검사는 중첩 {@code
   * whereConditions} 키가 빈 맵일 때 바깥 맵 크기가 1이라 지나가므로, 이 가드가 마지막 안전망이다.
   */
  protected void ensureWhereClause(final int appendedConditionCount) {
    if (appendedConditionCount <= 0) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
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
    } else if (value instanceof Number || value instanceof Boolean) {
      // 숫자와 Boolean은 안전하게 처리.
      // ID 같은 숫자는 문자열 다음으로 흔한데 예전에는 아래 시각/열거형 분기를 모두 지나서야 닿았다.
      // 아래 분기의 타입들(시각, Enum, Collection, Map)은 Number/Boolean 이 될 수 없어 결과는 같다.
      return value.toString();
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
    }

    // 기타 객체는 문자열로 변환 후 이스케이프
    String stringValue = value.toString();
    ensureValueLength(stringValue.length());
    return "'" + stringHelper.escapeSingleQuote(stringValue) + "'";
  }

  /**
   * SQL 리터럴 하나의 길이 상한을 확인한다.
   *
   * <p>예전에는 마지막 "기타 객체" 분기에만 있어서, 가장 흔한 {@code String} 은 검사를 통째로 건너뛰었다. 같은 길이의 {@code
   * StringBuilder} 는 걸리고 {@code String} 은 통과하는 상태였다.
   */
  private void ensureValueLength(final int length) {
    final int max = properties.getMaxStringValueLength();
    if (length > max) {
      throw new MybatisRepositoryException(
          "Value too long for SQL: " + length + ", max allowed: " + max);
    }
  }

  private String formatStringValue(final String str) {
    ensureValueLength(str.length());
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
    // 스트림 파이프라인과 joining 이 만들던 중간 문자열을 없애고 한 번에 이어 붙인다.
    // 곱을 int 로 계산하면 원소가 아주 많을 때 음수로 뒤집혀 NegativeArraySizeException 이 난다.
    // 초기 크기 힌트일 뿐이므로 long 으로 계산해 상한에서 자른다(배치 인서트와 같은 방식).
    final StringBuilder sb =
        new StringBuilder((int) Math.min((long) collection.size() * 12L + 4L, 1L << 20));
    sb.append("'[");
    boolean first = true;
    for (Object element : collection) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(formatValueForSQL(element).replace('\'', '"'));
    }
    return sb.append("]'").toString();
  }

  private String formatMapValue(final Map<?, ?> map) {
    // 예: "{\"key1\":val1, \"key2\":val2, ...}"
    // 기본 용량 16 은 JSON 한 조각도 못 담아 매번 재할당된다.
    StringBuilder sb = new StringBuilder(map.size() * 16 + 8).append("\"{");
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
