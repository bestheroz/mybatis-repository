package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import jakarta.persistence.Table;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.ibatis.jdbc.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

public class MybatisCommand {
  private static final Logger log = LoggerFactory.getLogger(MybatisCommand.class);

  // 캐싱: EntityClass -> Field[]
  private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();
  // 캐싱: EntityClass -> TableName
  private static final Map<Class<?>, String> TABLE_NAME_CACHE = new ConcurrentHashMap<>();
  // 캐싱: camelCase FieldName -> snake_case ColumnName
  private static final Map<String, String> COLUMN_NAME_CACHE = new ConcurrentHashMap<>();

  public static final String SELECT_ITEMS = "getDistinctAndTargetItemsByMapOrderByLimitOffset";
  public static final String SELECT_ITEM_BY_MAP = "getItemByMap";
  public static final String COUNT_BY_MAP = "countByMap";
  public static final String INSERT = "insert";
  public static final String INSERT_BATCH = "insertBatch";
  public static final String UPDATE_MAP_BY_MAP = "updateMapByMap";
  public static final String DELETE_BY_MAP = "deleteByMap";

  private static final String SEPARATOR = ":";
  private static final Set<String> METHOD_LIST =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  SELECT_ITEMS,
                  SELECT_ITEM_BY_MAP,
                  COUNT_BY_MAP,
                  INSERT,
                  INSERT_BATCH,
                  UPDATE_MAP_BY_MAP,
                  DELETE_BY_MAP)));

  public MybatisCommand() {}

  /**
   * 현재 stacktrace 에서 유효한 element 를 찾고, 그 인터페이스에서 제네릭으로 선언된 클래스 타입을 파싱해 반환한다.
   *
   * @return entity class
   */
  private Class<?> getEntityClass() {
    return Arrays.stream(new Throwable().getStackTrace())
        .filter(this::isValidStackTraceElement)
        .findFirst()
        .map(this::getClassFromStackTraceElement)
        .orElseThrow(() -> new MybatisRepositoryException("stackTraceElements is required"));
  }

  /**
   * StackTraceElement 중 METHOD_LIST 에 포함되는 메소드이며, 해당 클래스를 찾고 인터페이스 정보를 검증해서 올바른 인터페이스/제네릭이면 true 를
   * 반환한다.
   */
  private boolean isValidStackTraceElement(final StackTraceElement element) {
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

  /** StackTraceElement 에서 제네릭 인터페이스 정보를 파싱해 entity class 를 구한다. */
  private Class<?> getClassFromStackTraceElement(final StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      String genericInterface = clazz.getInterfaces()[0].getGenericInterfaces()[0].getTypeName();
      String className = substringBetween(genericInterface, "<", ">");
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      log.warn(getStackTrace(e));
      throw new MybatisRepositoryException("Failed::ClassNotFoundException", e);
    }
  }

  /** 현재 Entity class 에 매핑된 Table name 을 구한다. */
  public String getTableName() {
    return getTableName(this.getEntityClass());
  }

  /**
   * 특정 class 에 매핑된 Table name 을 구한다.
   *
   * @param entityClass Entity class
   * @return table 이름
   */
  public static String getTableName(final Class<?> entityClass) {
    // 캐싱된 값 있으면 반환
    if (TABLE_NAME_CACHE.containsKey(entityClass)) {
      return TABLE_NAME_CACHE.get(entityClass);
    }

    // 없으면 계산
    Table tableAnnotation = entityClass.getAnnotation(Table.class);
    String tableName;
    if (tableAnnotation != null) {
      tableName = tableAnnotation.name();
    } else {
      tableName = getCamelCaseToSnakeCase(entityClass.getSimpleName()).toLowerCase();
    }

    TABLE_NAME_CACHE.put(entityClass, tableName);
    return tableName;
  }

  /** 현재 Entity class 의 모든 필드 중 exclude되지 않은 필드명만 추출. */
  private Set<String> getEntityFields() {
    return this.getEntityFields(this.getEntityClass());
  }

  /** 특정 class 의 모든 필드 중 exclude되지 않은 필드명을 추출. */
  private <T> Set<String> getEntityFields(final Class<T> entityClass) {
    return Stream.of(getAllNonExcludedFields(entityClass))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  /** WHERE 절에 들어갈 map 조건이 필수적으로 존재해야 하므로, 없을 시 예외를 발생시킨다. */
  private void verifyWhereKey(final Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      log.warn("whereConditions is empty");
      throw new MybatisRepositoryException("'where' Conditions is required");
    }
  }

  /** Count 쿼리를 생성한다. */
  public String countByMap(final Map<String, Object> whereConditions) {
    SQL sql = new SQL().SELECT("COUNT(1) AS CNT").FROM(this.getTableName());
    getWhereSql(sql, whereConditions);
    log.debug("countByMap SQL: {}", sql);
    return sql.toString();
  }

  /** where 조건절이 있는 SELECT 쿼리를 만든다. (단건) */
  public String getItemByMap(final Map<String, Object> whereConditions) {
    verifyWhereKey(whereConditions);
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  /** SELECT 쿼리를 생성한다. distinctColumns, targetColumns, where, order, paging 등을 모두 반영. */
  public String getDistinctAndTargetItemsByMapOrderByLimitOffset(
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {

    SQL sql = new SQL();

    // SELECT 구문 구성
    appendSelectClause(sql, distinctColumns, targetColumns);

    // FROM 구문
    sql.FROM(this.getTableName());

    // LIMIT / OFFSET
    if (limit != null) {
      sql.LIMIT(limit);
    }
    if (offset != null) {
      sql.OFFSET(offset);
    }

    // WHERE 절
    getWhereSql(sql, whereConditions);

    // ORDER 절
    appendOrderByClause(sql, orderByConditions);

    log.debug("getDistinctAndTargetItemsByMapOrderByLimitOffset SQL: {}", sql);
    return sql.toString();
  }

  /** INSERT (단건) 쿼리 생성 */
  public <T> String insert(@NonNull final T entity) {
    SQL sql = new SQL();
    sql.INSERT_INTO(getTableName(entity.getClass()));
    Map<String, Object> entityMap = toMap(entity);

    for (Map.Entry<String, Object> entry : entityMap.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      // exclude 필드 제외
      if (!MybatisProperties.getExcludeFields().contains(key)) {
        sql.VALUES(wrapIdentifier(getColumnName(key)), getFormattedValue(value));
      }
    }

    log.debug("insert SQL: {}", sql);
    return sql.toString();
  }

  /** INSERT (다건) 쿼리 생성 */
  public <T> String insertBatch(@NonNull final List<T> entities) {
    if (entities.isEmpty()) {
      log.warn("entities are empty");
      throw new MybatisRepositoryException("entities empty");
    }

    SQL sql = new SQL();
    Class<?> entityClass = entities.get(0).getClass();
    String tableName = wrapIdentifier(getTableName(entityClass));
    sql.INSERT_INTO(tableName);

    // 공통으로 삽입할 컬럼
    Set<String> columns = getEntityFields(entityClass);

    // 필드명을 snake_case 로 변경한 뒤, exclude 필드가 아닌 것만 columns 로 구성
    List<String> columnList =
        columns.stream()
            .filter(col -> !MybatisProperties.getExcludeFields().contains(col))
            .map(MybatisCommand::getColumnName)
            .collect(Collectors.toList());

    String columnsJoined =
        columnList.stream().map(this::wrapIdentifier).collect(Collectors.joining(", "));
    sql.INTO_COLUMNS(columnsJoined);

    // 각 row 데이터 생성
    List<List<String>> valuesList = new ArrayList<>();
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      List<String> rowValueList = new ArrayList<>();
      for (String column : columns) {
        // 컬럼 순서에 맞춰 값 추출 (String -> SQL value)
        rowValueList.add(getFormattedValue(entityMap.get(column)));
      }
      valuesList.add(rowValueList);
    }

    // VALUES(...) 구문들 생성
    String valuesJoined =
        valuesList.stream()
            .map(value -> String.join(", ", value))
            .collect(Collectors.joining("), ("));
    sql.INTO_VALUES(valuesJoined);

    log.debug("insertBatch SQL: {}", sql);
    return sql.toString();
  }

  /** UPDATE 쿼리 생성 */
  public String updateMapByMap(
      final Map<String, Object> updateMap, final Map<String, Object> whereConditions) {
    verifyWhereKey(whereConditions);

    SQL sql = new SQL().UPDATE(getTableName());
    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      String javaFieldName = entry.getKey();
      Object value = entry.getValue();
      if (!MybatisProperties.getExcludeFields().contains(javaFieldName)) {
        String dbColumnName = getColumnName(javaFieldName);
        sql.SET(getEqualSql(dbColumnName, value));
      }
    }

    getWhereSql(sql, whereConditions);
    requireWhereClause(sql);
    log.debug("updateMapByMap SQL: {}", sql);
    return sql.toString();
  }

  /** DELETE 쿼리 생성 */
  public String deleteByMap(final Map<String, Object> whereConditions) {
    verifyWhereKey(whereConditions);
    SQL sql = new SQL().DELETE_FROM(this.getTableName());
    getWhereSql(sql, whereConditions);
    requireWhereClause(sql);
    log.debug("deleteByMap SQL: {}", sql);
    return sql.toString();
  }

  /** WHERE 절이 필수적으로 들어가야 하는 쿼리(UPDATE, DELETE)에서 확인용. */
  private void requireWhereClause(final SQL sql) {
    if (!sql.toString().toLowerCase().contains("where ")) {
      log.warn("whereConditions are empty");
      throw new MybatisRepositoryException("whereConditions are required");
    }
  }

  /** SELECT 구문을 구성하는 로직. distinctColumns, targetColumns 가 모두 비어 있으면 entity 의 모든 필드를 SELECT */
  private void appendSelectClause(SQL sql, Set<String> distinctColumns, Set<String> targetColumns) {
    // distinctColumns, targetColumns 모두 비어 있으면, 전체 컬럼 SELECT
    if (distinctColumns.isEmpty() && targetColumns.isEmpty()) {
      for (String field : this.getEntityFields()) {
        sql.SELECT(wrapIdentifier(getColumnName(field)));
      }
      return;
    }

    // distinctColumns
    for (String distinctCol : distinctColumns) {
      sql.SELECT_DISTINCT(wrapIdentifier(getColumnName(distinctCol)));
    }

    // targetColumns
    for (String targetCol : targetColumns) {
      if (!distinctColumns.contains(targetCol)) {
        sql.SELECT(wrapIdentifier(getColumnName(targetCol)));
      }
    }
  }

  /** Order By 구문 구성 orderByConditions 의 원소가 '-' 로 시작하면 desc 정렬 */
  private void appendOrderByClause(SQL sql, List<String> orderByConditions) {
    for (String condition : orderByConditions) {
      if (condition.startsWith("-")) {
        // "-" 뒤 실제 컬럼명 추출
        String realCol = condition.substring(1);
        sql.ORDER_BY(wrapIdentifier(getColumnName(realCol)) + " DESC");
      } else {
        sql.ORDER_BY(wrapIdentifier(getColumnName(condition)) + " ASC");
      }
    }
  }

  /** WHERE 절에 들어갈 조건들을 SQL 객체에 반영한다. */
  private void getWhereSql(final SQL sql, final Map<String, Object> whereConditions) {
    if (whereConditions == null) {
      return;
    }

    for (Map.Entry<String, Object> entry : whereConditions.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      // key를 ':' 기준으로 split
      String columnName = substringBefore(key);
      String conditionType = substringAfter(key);
      if (conditionType.isEmpty()) {
        conditionType = "eq"; // 별도 타입이 없으면 eq
      }

      // columnName 은 camelCase -> snakeCase 변환 (캐싱)
      String dbColumnName = getColumnName(columnName);
      String whereClause = getWhereString(conditionType, dbColumnName, value);
      sql.WHERE(whereClause);
    }
  }

  /** conditionType 에 따른 WHERE 조건 문자열 생성 */
  private String getWhereString(
      final String conditionType, final String dbColumnName, final Object value) {
    switch (conditionType) {
      case "ne":
      case "not":
        return String.format("`%s` <> %s", dbColumnName, getFormattedValue(value));
      case "in":
        return buildInClause(dbColumnName, value, false);
      case "notIn":
        return buildInClause(dbColumnName, value, true);
      case "null":
        return String.format("`%s` IS NULL", dbColumnName);
      case "notNull":
        return String.format("`%s` IS NOT NULL", dbColumnName);
      case "contains":
        return String.format("INSTR(`%s`, %s) > 0", dbColumnName, getFormattedValue(value));
      case "notContains":
        return String.format("INSTR(`%s`, %s) = 0", dbColumnName, getFormattedValue(value));
      case "startsWith":
        return String.format("INSTR(`%s`, %s) = 1", dbColumnName, getFormattedValue(value));
      case "endsWith":
        return String.format(
            "RIGHT(`%s`, CHAR_LENGTH(%s)) = %s",
            dbColumnName, getFormattedValue(value), getFormattedValue(value));
      case "lt":
        return String.format("`%s` < %s", dbColumnName, getFormattedValue(value));
      case "lte":
        return String.format("`%s` <= %s", dbColumnName, getFormattedValue(value));
      case "gt":
        return String.format("`%s` > %s", dbColumnName, getFormattedValue(value));
      case "gte":
        return String.format("`%s` >= %s", dbColumnName, getFormattedValue(value));
      case "eq":
      default:
        return getEqualSql(dbColumnName, value);
    }
  }

  /** IN / NOT IN 구문 빌더 */
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
        inValues.stream().map(this::getFormattedValue).collect(Collectors.joining(", "));
    return String.format("`%s` %s IN (%s)", dbColumnName, (isNotIn ? "NOT" : ""), inFormatted);
  }

  /** '=' 조건 문자열 생성 */
  private String getEqualSql(final String dbColumnName, final Object value) {
    return String.format("`%s` = %s", dbColumnName, getFormattedValue(value));
  }

  /**
   * DB에 들어갈 값으로 변환 - 문자열은 quote - date/time 은 지정된 포맷으로 - Enum 은 ValueEnum.getValue() 또는 name() -
   * Collection, Map 은 JSON 형태 등 - 그 외엔 toString()
   */
  private String getFormattedValue(final Object value) {
    if (value == null) {
      return "null";
    }
    // 분기
    if (value instanceof String) {
      return formatStringValue((String) value);
    }
    if (value instanceof Instant) {
      return formatInstantValue((Instant) value);
    }
    if (value instanceof Date) {
      return formatDateValue((Date) value);
    }
    if (value instanceof LocalDateTime) {
      return formatLocalDateTimeValue((LocalDateTime) value);
    }
    if (value instanceof LocalDate) {
      return formatLocalDateValue((LocalDate) value);
    }
    if (value instanceof OffsetDateTime) {
      return formatOffsetDateTime((OffsetDateTime) value);
    }
    if (value instanceof Enum<?>) {
      return formatEnumValue((Enum<?>) value);
    }
    if (value instanceof Collection<?>) {
      return formatCollectionValue((Collection<?>) value);
    }
    if (value instanceof Map<?, ?>) {
      return formatMapValue((Map<?, ?>) value);
    }
    // 기본 (숫자, boolean 등)
    return escapeSingleQuote(value.toString());
  }

  private String formatStringValue(String str) {
    if (isISO8601String(str)) {
      return "'" + converterInstantToString(Instant.parse(str), "yyyy-MM-dd HH:mm:ss.SSS") + "'";
    }
    return "'" + escapeSingleQuote(str) + "'";
  }

  private String formatInstantValue(Instant instant) {
    return "'" + converterInstantToString(instant, "yyyy-MM-dd HH:mm:ss.SSS") + "'";
  }

  private String formatDateValue(Date date) {
    return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(date) + "'";
  }

  private String formatLocalDateTimeValue(LocalDateTime localDateTime) {
    return "'" + localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")) + "'";
  }

  private String formatLocalDateValue(LocalDate localDate) {
    return "'" + localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "'";
  }

  private String formatOffsetDateTime(OffsetDateTime offsetDateTime) {
    return "'"
        + converterInstantToString(offsetDateTime.toInstant(), "yyyy-MM-dd HH:mm:ss.SSS")
        + "'";
  }

  private String formatEnumValue(Enum<?> enumValue) {
    if (enumValue instanceof ValueEnum) {
      return "'" + ((ValueEnum) enumValue).getValue() + "'";
    }
    return "'" + enumValue.name() + "'";
  }

  private String formatCollectionValue(Collection<?> collection) {
    // SQL 직렬화 시에는 문자열 quote 이슈가 있으므로 주의
    String joined =
        collection.stream()
            .map(v -> getFormattedValue(v).replace("'", "\""))
            .collect(Collectors.joining(", "));
    return "'[" + joined + "]'";
  }

  private String formatMapValue(Map<?, ?> map) {
    // SQL 직렬화 시에는 문자열 quote 이슈가 있으므로 주의
    StringBuilder sb = new StringBuilder().append("\"{");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append("\"")
          .append(escapeSingleQuote(String.valueOf(entry.getKey())))
          .append("\":")
          .append(getFormattedValue(entry.getValue()));
    }
    sb.append("}\"");
    return sb.toString();
  }

  private String escapeSingleQuote(String src) {
    return src.replace("'", "''");
  }

  /** Instant -> String 변환 (UTC 기준) */
  private String converterInstantToString(final Instant instant, final String pattern) {
    return OffsetDateTime.ofInstant(instant, ZoneId.of("UTC"))
        .format(DateTimeFormatter.ofPattern(pattern));
  }

  /** ISO8601 형식 문자열인지 간단하게 체크. 예: 2021-01-01T01:23:45Z, 2021-01-01T01:23:45+09:00 */
  private boolean isISO8601String(final String value) {
    // 실제로는 more strict한 검증 혹은 try-catch로 파싱을 시도할 수도 있음
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

  /** camelCase -> snake_case 변환 + 캐싱. 외부에서 직접 써야 할 때는 {@link #getColumnName(String)}로 접근 */
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

  /** 컬럼명 캐싱하여 반환: camelCase -> snake_case */
  private static String getColumnName(String fieldName) {
    return COLUMN_NAME_CACHE.computeIfAbsent(fieldName, MybatisCommand::getCamelCaseToSnakeCase);
  }

  /** 엔티티 객체 -> Map 변환 */
  public static Map<String, Object> toMap(final Object source) {
    final Map<String, Object> map = new HashMap<>();
    final Field[] fields = getAllNonExcludedFields(source.getClass());
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

  /** 클래스 및 부모 클래스의 모든 필드를 얻는다(캐싱 사용). */
  private static Field[] getAllNonExcludedFields(Class<?> clazz) {
    if (FIELD_CACHE.containsKey(clazz)) {
      return FIELD_CACHE.get(clazz);
    }

    List<Field> allFields = new ArrayList<>();
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      allFields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
      currentClass = currentClass.getSuperclass();
    }

    Field[] fieldArray =
        allFields.stream()
            .filter(field -> !MybatisProperties.getExcludeFields().contains(field.getName()))
            .distinct()
            .toArray(Field[]::new);
    FIELD_CACHE.put(clazz, fieldArray);
    return fieldArray;
  }

  /** 문자열의 특정 구간을 추출 (open, close 사이) */
  private static String substringBetween(String str, String open, String close) {
    if (str == null) {
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

  /** ':' 구분자 앞 부분 */
  private static String substringBefore(String str) {
    if (str == null) {
      return "";
    }
    int pos = str.indexOf(SEPARATOR);
    if (pos == -1) {
      return str;
    }
    return str.substring(0, pos);
  }

  /** ':' 구분자 뒷 부분 */
  private static String substringAfter(String str) {
    if (str == null) {
      return "";
    }
    int pos = str.indexOf(SEPARATOR);
    if (pos == -1) {
      return "";
    }
    return str.substring(pos + SEPARATOR.length());
  }

  /** 예외 스택트레이스를 문자열로 변환 */
  private static String getStackTrace(Throwable e) {
    // 실제 운영환경에서는 trace 로그를 찍을지 여부를 신중하게 결정
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  /** DB Identifier wrapping, 예: `column_name` */
  private String wrapIdentifier(final String identifier) {
    return "`" + identifier + "`";
  }
}
