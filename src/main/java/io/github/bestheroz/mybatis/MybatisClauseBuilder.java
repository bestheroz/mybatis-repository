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

  // 상수 정의.
  // 날짜시각 패턴은 MybatisStringHelper 의 것을 그대로 쓴다. 같은 값을 두 벌 적어 두면
  // instantToString 안의 formatterOf 가 "미리 만들어 둔 포매터" 를 고르는 판정(패턴 문자열 비교)이
  // 한쪽만 고치는 순간 조용히 빗나가, 값 하나마다 DateTimeFormatter 를 새로 만들게 된다.
  private static final String DEFAULT_DATETIME_FORMAT = MybatisStringHelper.DEFAULT_DATETIME_FORMAT;
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

  /** MyBatis 가 ParamMap 안에 사용자의 조건 맵을 담아 두는 키. */
  private static final String WHERE_CONDITIONS_KEY = "whereConditions";

  /**
   * 프로바이더가 받은 맵에서 실제 조건 맵을 되찾는다.
   *
   * <p>인자가 하나뿐인 프로바이더({@code buildSelectOneSQL}/{@code buildCountSQL}/{@code buildDeleteSQL})에
   * MyBatis 는 사용자가 넘긴 맵이 아니라 {@code ParamMap} 전체를 넘긴다. 즉 여기 들어오는 맵은 보통 {@code {context=null,
   * whereConditions={...}, param1=null, param2={...}}} 이고, 조건 맵은 그 안에 있다.
   *
   * <p>{@code ParamMap#get} 은 없는 키에 {@code null} 이 아니라 {@code BindingException} 을 던지므로 {@code
   * containsKey} 로 먼저 본다.
   */
  @SuppressWarnings("unchecked") // 조건 맵의 값 타입은 프로토콜상 호출부가 보장한다
  protected Map<String, Object> extractWhereConditions(final Map<String, Object> params) {
    if (params == null) {
      return Collections.emptyMap();
    }
    if (!params.containsKey(WHERE_CONDITIONS_KEY)) {
      return params;
    }
    final Object whereConditions = params.get(WHERE_CONDITIONS_KEY);
    if (whereConditions instanceof Map) {
      return (Map<String, Object>) whereConditions;
    }
    if (whereConditions == null) {
      // 소비자가 조건 맵 자리에 null 을 넘긴 경우다(getItemByMap(null) 등). 예전에는 ParamMap 을
      // 통째로 조건 맵으로 되돌려 주어 context/param1/param2 를 필드 이름으로 찾다가
      // "entity 에 포함되지 않는 필드 발견 : context" 라는, 원인을 알 수 없는 예외로 끝났다.
      return Collections.emptyMap();
    }
    // 키는 있는데 맵도 null 도 아니면 ParamMap 이 아니라 사용자의 조건 맵이고, whereConditions 라는
    // 이름의 필드를 거르려는 것이다. 예전 동작을 그대로 둔다.
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
      // 이어 붙인 목록을 캐시에서 한 번에 가져온다. 컬럼이 없으면 SELECT 를 부르지 않아야
      // 빈 컬럼 하나가 목록에 들어가는 것을 피할 수 있다(컬럼을 하나씩 넘기던 때와 같은 문장).
      final String columns = entityHelper.getSelectColumnList(entityClass);
      if (!columns.isEmpty()) {
        sql.SELECT(columns);
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
      if (condition == null) {
        // 이 클래스의 다른 잘못된 입력은 모두 MybatisRepositoryException 으로 끝난다.
        // 여기만 맨 NullPointerException 이 나가면 부르는 쪽이 같은 방식으로 다룰 수 없다.
        throw new MybatisRepositoryException("orderByConditions contains a null element");
      }
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
    // 곱을 int 로 계산하면 maxInClauseSize 를 크게 올려 둔 소비자에게서 음수로 뒤집혀
    // NegativeArraySizeException 이 난다. 초기 크기 힌트일 뿐이므로 formatCollectionValue 와
    // 같은 방식으로 long 으로 계산해 상한에서 자른다.
    final StringBuilder sb =
        new StringBuilder(
            (int) Math.min(dbColumnName.length() + (long) inValues.size() * 8L + 16L, 1L << 20));
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
    return buildComparisonClause(dbColumnName, "=", value);
  }

  /**
   * {@code `column` <op> value} 모양의 절을 만든다. eq/ne/lt/lte/gt/gte 가 연산자만 다른 같은 문장이라 한자리에 모은다.
   *
   * <p>속도 때문이 아니다. 재어 보면 예전의 {@code "`" + col + "` = " + v} 와 나노초 단위 잡음 안에서 오간다 -- JIT 가 인라인 연결식을
   * 이미 한 번에 크기를 잡아 처리하므로 미리 잡아 얻는 것이 없다. 값을 SQL 텍스트로 만드는 자리를 이 클래스 하나에 모아 두는 것이 목적이다. 분기마다 SQL 을 직접
   * 이어 붙이면 언젠가 따옴표도 직접 붙이게 되고, 그 순간 이스케이프 관문을 비켜 간다.
   */
  protected String buildComparisonClause(
      final String dbColumnName, final String operator, final Object value) {
    final String formatted = formatValueForSQL(value);
    return new StringBuilder(dbColumnName.length() + operator.length() + formatted.length() + 4)
        .append('`')
        .append(dbColumnName)
        .append("` ")
        .append(operator)
        .append(' ')
        .append(formatted)
        .toString();
  }

  /**
   * {@code INSTR(`column`, value) <comparison>} 모양의 절을 만든다. contains/notContains/startsWith 가 뒤의
   * 비교만 다른 같은 문장이다.
   */
  protected String buildInstrClause(
      final String dbColumnName, final Object value, final String comparison) {
    final String formatted = formatValueForSQL(value);
    return new StringBuilder(dbColumnName.length() + formatted.length() + comparison.length() + 12)
        .append("INSTR(`")
        .append(dbColumnName)
        .append("`, ")
        .append(formatted)
        .append(") ")
        .append(comparison)
        .toString();
  }

  /**
   * {@code RIGHT(`column`, CHAR_LENGTH(value)) = value}. 같은 값을 두 번 쓰므로 한 번만 포맷한다(문자열이면 이스케이프도 한 번만
   * 돈다).
   */
  protected String buildEndsWithClause(final String dbColumnName, final Object value) {
    final String formatted = formatValueForSQL(value);
    return new StringBuilder(dbColumnName.length() + formatted.length() * 2 + 28)
        .append("RIGHT(`")
        .append(dbColumnName)
        .append("`, CHAR_LENGTH(")
        .append(formatted)
        .append(")) = ")
        .append(formatted)
        .toString();
  }

  /** {@code `column` IS NULL} / {@code `column` IS NOT NULL}. */
  protected String buildNullClause(final String dbColumnName, final boolean isNotNull) {
    return new StringBuilder(dbColumnName.length() + 16)
        .append('`')
        .append(dbColumnName)
        .append(isNotNull ? "` IS NOT NULL" : "` IS NULL")
        .toString();
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
      if (value instanceof Double || value instanceof Float) {
        // NaN/Infinity 는 toString 이 맨 낱말을 내놓아 `score` = NaN 이 되고, DB 는 이것을 컬럼
        // 이름으로 읽어 "Unknown column 'NaN'" 을 낸다. 숫자로 적을 방법이 없으니 여기서 끊는다.
        ensureFiniteNumber((Number) value);
      }
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

    // 배열은 toString() 이 신원 해시를 내놓는다. byte[] 컬럼(BLOB/VARBINARY)이 '[B@78e03bb5' 로
    // 저장되는데도 INSERT 는 성공해서, 값이 망가진 것을 아무도 모르는 채로 지나간다.
    if (value.getClass().isArray()) {
      return formatArrayValue(value);
    }

    // 기타 객체는 문자열로 변환 후 이스케이프
    String stringValue = value.toString();
    ensureValueLength(stringValue.length());
    return stringHelper.quoteAndEscape(stringValue);
  }

  private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

  /**
   * {@code byte[]} 만 SQL 로 적을 방법이 있다 -- MySQL/MariaDB 의 {@code X'..'} 16진 리터럴이다. 나머지 배열은 원소를 어떤
   * 모양으로 적어야 하는지 정해진 답이 없으므로, {@code toString()} 의 신원 해시를 조용히 저장하는 대신 예외로 끊는다.
   */
  private String formatArrayValue(final Object value) {
    if (!(value instanceof byte[])) {
      throw new MybatisRepositoryException(
          "Unsupported array type for SQL value: " + value.getClass().getName());
    }
    final byte[] bytes = (byte[]) value;
    // X'' 세 글자에 바이트마다 두 글자. 곱을 int 로 하면 큰 배열에서 음수로 뒤집힌다.
    final long length = (long) bytes.length * 2L + 3L;
    ensureValueLength((int) Math.min(length, Integer.MAX_VALUE));
    final StringBuilder sb = new StringBuilder((int) Math.min(length, 1L << 20));
    sb.append("X'");
    for (byte b : bytes) {
      sb.append(HEX_DIGITS[(b >> 4) & 0xF]).append(HEX_DIGITS[b & 0xF]);
    }
    return sb.append('\'').toString();
  }

  /** 숫자로 적을 수 없는 {@code Double}/{@code Float} 값을 걸러 낸다. */
  private void ensureFiniteNumber(final Number value) {
    final double d = value.doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new MybatisRepositoryException("Value is not a finite number for SQL: " + value);
    }
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
    return stringHelper.quoteAndEscape(str);
  }

  private String formatEnumValue(final Enum<?> enumValue) {
    if (enumValue instanceof ValueEnum) {
      ValueEnum ve = (ValueEnum) enumValue;
      // getValue() 는 구현하는 쪽이 정하는 임의의 문자열이라 다른 값과 똑같이 이스케이프를 거쳐야 한다.
      return stringHelper.quoteAndEscape(ve.getValue());
    }
    // 기본 name() (자바 식별자라 바뀔 문자는 없지만 경로를 하나로 맞춘다)
    return stringHelper.quoteAndEscape(enumValue.name());
  }

  private String formatCollectionValue(final Collection<?> collection) {
    // 예: '["val1", "val2", 3]' 형태
    // 스트림 파이프라인과 joining 이 만들던 중간 문자열을 없애고 한 번에 이어 붙인다.
    // 곱을 int 로 계산하면 원소가 아주 많을 때 음수로 뒤집혀 NegativeArraySizeException 이 난다.
    // 초기 크기 힌트일 뿐이므로 long 으로 계산해 상한에서 자른다(배치 인서트와 같은 방식).
    final StringBuilder body =
        new StringBuilder((int) Math.min((long) collection.size() * 12L + 4L, 1L << 20));
    appendCollectionBody(body, collection);
    // 예전에는 원소 하나하나만 길이를 봤기 때문에, 상한 바로 아래 길이의 값을 N개 담으면
    // 아무 제한 없이 커진 리터럴이 그대로 나갔다. 폭주 방지선은 완성된 리터럴에 걸어야 한다.
    ensureValueLength(body.length());
    return stringHelper.quoteAndEscape(body.toString());
  }

  private void appendCollectionBody(final StringBuilder body, final Collection<?> collection) {
    body.append('[');
    boolean first = true;
    for (Object element : collection) {
      if (!first) {
        body.append(", ");
      }
      first = false;
      appendEmbeddedValue(body, element);
    }
    body.append(']');
  }

  private String formatMapValue(final Map<?, ?> map) {
    // 예: '{"key1":"val1", "key2":42}'
    // 기본 용량 16 은 JSON 한 조각도 못 담아 매번 재할당된다. 곱셈은 형제 메소드들과 같이
    // long 으로 계산한다 -- 이쪽은 IN 절과 달리 앞단에 개수 상한이 아예 없다.
    final StringBuilder body =
        new StringBuilder((int) Math.min((long) map.size() * 16L + 8L, 1L << 20));
    appendMapBody(body, map);
    ensureValueLength(body.length());
    return stringHelper.quoteAndEscape(body.toString());
  }

  private void appendMapBody(final StringBuilder body, final Map<?, ?> map) {
    body.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        body.append(", ");
      }
      first = false;
      body.append('"').append(String.valueOf(entry.getKey())).append("\":");
      appendEmbeddedValue(body, entry.getValue());
    }
    body.append('}');
  }

  /**
   * 컬렉션/맵 본문에 값 하나를 붙인다. <b>여기서는 SQL 이스케이프를 하지 않는다</b> -- 완성된 본문 전체가 마지막에 {@link
   * MybatisStringHelper#quoteAndEscape} 를 딱 한 번 지나가기 때문이다. 이스케이프를 한 번만 하는 것이 이 메소드의 존재 이유다.
   *
   * <p>예전에는 원소마다 이스케이프까지 끝낸 SQL 리터럴을 만든 뒤 작은따옴표를 큰따옴표로 바꿔치웠다({@code
   * formatValueForSQL(e).replace('\'', '"')}). 그러면 이스케이프로 생긴 {@code ''} 짝이 {@code ""} 가 되어 {@code
   * O'Brien} 이 {@code O""Brien} 으로 저장됐다. 되돌릴 수 없는 조용한 데이터 손상이다.
   *
   * <p>그래서 사용자 텍스트가 들어 있는 {@code String} 과 {@code Enum} 은 날것 그대로 넣는다. 나머지 타입은 숫자·불리언·시각처럼 라이브러리가
   * 형식을 정하는 값이라 사용자 텍스트가 섞이지 않으므로, 정해진 리터럴을 그대로 쓰고 감싼 따옴표만 바꾼다.
   */
  private void appendEmbeddedValue(final StringBuilder body, final Object value) {
    if (value instanceof String) {
      final String str = (String) value;
      body.append('"');
      // 문자열 원소의 ISO-8601 변환은 예전과 똑같이 유지한다.
      if (stringHelper.isISO8601String(str)) {
        final Instant instant = stringHelper.parseIso8601(str);
        if (instant != null) {
          body.append(stringHelper.instantToString(instant, DEFAULT_DATETIME_FORMAT)).append('"');
          return;
        }
      }
      body.append(str).append('"');
      return;
    }
    if (value instanceof Enum) {
      final Enum<?> enumValue = (Enum<?>) value;
      final String raw =
          enumValue instanceof ValueEnum ? ((ValueEnum) enumValue).getValue() : enumValue.name();
      body.append('"').append(raw).append('"');
      return;
    }
    // 중첩 컨테이너는 본문에 바로 이어 붙인다. formatValueForSQL 로 돌리면 안쪽이 이미 이스케이프를
    // 끝낸 리터럴을 돌려주고, 바깥에서 한 번 더 이스케이프되어 중첩 배열이 "문자열 하나" 로 뭉개진다.
    if (value instanceof Collection) {
      appendCollectionBody(body, (Collection<?>) value);
      return;
    }
    if (value instanceof Map) {
      appendMapBody(body, (Map<?, ?>) value);
      return;
    }
    body.append(formatValueForSQL(value).replace('\'', '"'));
  }
}
