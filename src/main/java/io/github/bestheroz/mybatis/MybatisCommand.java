package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.builder.annotation.ProviderContext;
import org.apache.ibatis.jdbc.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MybatisCommand {
  private static final Logger log = LoggerFactory.getLogger(MybatisCommand.class);

  // ======================
  // Thread-safe Caches
  // ======================
  protected static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
  protected static final Map<Class<?>, String> TABLE_NAME_CACHE = new ConcurrentHashMap<>();

  /** 엔티티의 @Column 필드 이름 집합. getEntityFields 가 질의마다 새 Set 을 만들지 않게 한다. */
  protected static final Map<Class<?>, Set<String>> FIELD_NAME_CACHE = new ConcurrentHashMap<>();

  /**
   * (엔티티, 자바 필드명) → DB 컬럼명. 이 조회는 컬럼 하나마다 어노테이션 배열 사본과 리플렉션 Method 조회를 만들어 내는데, 결과는 JVM 이 사는 동안 바뀌지
   * 않는다.
   */
  protected static final Map<Class<?>, Map<String, String>> COLUMN_NAME_CACHE =
      new ConcurrentHashMap<>();

  /** 매퍼 인터페이스 → 엔티티 클래스. 제네릭 인터페이스 탐색이 질의마다 다시 돌지 않게 한다. */
  protected static final Map<Class<?>, Class<?>> MAPPER_ENTITY_CACHE = new ConcurrentHashMap<>();

  /**
   * (엔티티, 자바 필드명) → 백틱으로 감싼 DB 컬럼명. 전체 컬럼 SELECT 는 질의마다 컬럼 수만큼 식별자 검증과 문자열 이어붙이기를 다시 했다. 컬럼명은 JVM 이
   * 사는 동안 바뀌지 않으므로 감싼 결과째로 담아 둔다.
   */
  protected static final Map<Class<?>, Map<String, String>> WRAPPED_COLUMN_CACHE =
      new ConcurrentHashMap<>();

  /**
   * 엔티티 → {@code FIELD_NAME_CACHE} 순회 순서에 맞춘 {@link Field} 목록. 인서트가 값을 꺼낼 때 {@code toMap} 으로 Map 을
   * 만들지 않고 바로 읽게 한다.
   */
  protected static final Map<Class<?>, List<Field>> ORDERED_FIELD_CACHE = new ConcurrentHashMap<>();

  /**
   * 엔티티 → 전체 컬럼 SELECT 에 쓰는, {@code ", "} 로 이어 붙인 백틱 컬럼 목록.
   *
   * <p>가장 흔한 질의 모양인 전체 컬럼 SELECT 는 컬럼마다 캐시 조회 두 번과 {@code SQL#SELECT} 호출 한 번을 되풀이했다. 이어 붙인 결과는 JVM
   * 이 사는 동안 바뀌지 않고, MyBatis 는 SELECT 목록을 어차피 {@code ", "} 로 잇기 때문에 한 번에 넘겨도 만들어지는 문장이 같다.
   */
  protected static final Map<Class<?>, String> SELECT_COLUMNS_CACHE = new ConcurrentHashMap<>();

  // ======================
  // Allowed Method List (기존과 동일)
  // ======================
  public static final String SELECT_ITEMS = "buildSelectSQL";
  public static final String SELECT_ITEM_BY_MAP = "buildSelectOneSQL";
  public static final String COUNT_BY_MAP = "buildCountSQL";
  public static final String INSERT = "buildInsertSQL";
  public static final String INSERT_BATCH = "buildInsertBatchSQL";
  public static final String UPDATE_MAP_BY_MAP = "buildUpdateSQL";
  public static final String DELETE_BY_MAP = "buildDeleteSQL";

  private final MybatisEntityHelper entityHelper;
  private final MybatisClauseBuilder clauseBuilder;

  // 싱글톤 인스턴스들 (성능 최적화)
  private static final MybatisStringHelper SHARED_STRING_HELPER = new MybatisStringHelper();
  private static final MybatisEntityHelper SHARED_ENTITY_HELPER =
      new MybatisEntityHelper(SHARED_STRING_HELPER);
  private static final MybatisClauseBuilder SHARED_CLAUSE_BUILDER =
      new MybatisClauseBuilder(SHARED_STRING_HELPER, SHARED_ENTITY_HELPER);

  // 상수로 정의하여 객체 생성 방지
  private static final Set<String> EMPTY_SET = Collections.emptySet();
  private static final List<String> EMPTY_LIST = Collections.emptyList();

  /**
   * INSERT 의 값 자리에서 {@code null} 대신 쓰는 키워드.
   *
   * <p>엔티티 경로에서 {@code null} 은 "이 컬럼은 정하지 않았다" 는 뜻이지 "NULL 을 넣어라" 가 아니다. {@code null} 리터럴을 그대로 내면
   * {@code NOT NULL DEFAULT} 가 걸린 컬럼이 기본값을 받지 못하고 {@code Column 'X' cannot be null} 로 거부된다.
   * MySQL/MariaDB 의 {@code INSERT ... VALUES (DEFAULT)} 는 그 자리만 기본값으로 채우므로, 컬럼 목록을 손대지 않고도(=배치의 컬럼
   * 집합과 {@code ORDERED_FIELD_CACHE} 순서 불변식을 건드리지 않고도) 원하는 뜻을 낼 수 있다.
   *
   * <p>AUTO_INCREMENT PK 에 {@code DEFAULT} 가 들어가면 {@code null} 과 똑같이 자동 증가로 처리된다. MariaDB 는
   * KB(AUTO_INCREMENT) 가 "NULL or DEFAULT" 로 명시한다. MySQL 레퍼런스에는 그 문장이 없고, "AUTO_INCREMENT 컬럼의 기본값은
   * 시퀀스의 다음 값"(Data Type Default Values) + "DEFAULT 는 컬럼을 기본값으로 명시 설정"(INSERT) 두 문서를 이은 추론이다.
   *
   * <p>WHERE 절과 맵 경로 UPDATE 는 여전히 {@code null} 리터럴이 필요하므로 {@code formatValueForSQL} 자체는 그대로 두고
   * INSERT 렌더링 지점에서만 갈라진다.
   */
  private static final String DEFAULT_KEYWORD = "DEFAULT";

  /**
   * SQL 을 한 번만 문자열로 만들고, 디버그 로그가 켜져 있을 때만 개행을 지운 사본을 만든다.
   *
   * <p>{@code log.debug("{}", sql.toString().replaceAll(...))} 는 로그가 꺼져 있어도 인자를 먼저 계산한다. 즉 레벨과 무관하게
   * SQL 전체 사본과 정규식 Pattern 이 매번 만들어졌다. 배치 인서트처럼 SQL 이 큰 경우 그대로 낭비다.
   */
  private static String renderAndLog(
      final SQL sql, final String label, final long estimatedLength) {
    final String rendered = renderPreSized(sql, estimatedLength);
    logSql(label, rendered);
    return rendered;
  }

  /**
   * 완성될 크기를 미리 잡아 둔 버퍼에 SQL 을 써 넣는다.
   *
   * <p>{@code AbstractSQL#toString} 은 {@code new StringBuilder()} 로 시작한다. 기본 용량이 16 이라 문장이 그보다 길면
   * 버퍼가 두 배씩 늘어나며, 그때마다 지금까지 쓴 내용을 통째로 새 배열에 옮긴다. 결과적으로 최종 크기의 두 배쯤을 복사하고 그만큼의 배열을 버린다. 배치
   * 인서트(1000행 x 20컬럼이면 300 KB를 넘는다)만의 문제가 아니다 -- 20컬럼 엔티티의 평범한 문장도 155~404자라 16에서 다섯에서 여섯 번 늘어난다.
   * 재어 보면 그 버려지는 배열이 문장 하나를 만드는 데 드는 할당의 10~25%다(전체 컬럼 SELECT 1,775B 중 약 450B, 20컬럼 INSERT 5,359B 중
   * 약 870B).
   *
   * <p>{@code usingAppender} 는 {@code toString} 이 쓰는 것과 같은 {@code sql(Appendable)} 경로를 그대로 돌리므로
   * 만들어지는 문자열이 글자까지 같다. 크기 힌트만 미리 주는 것이라 정확하지 않아도 정확성에는 영향이 없다 -- 모자라면 늘어나고 남으면 여유로 남는다. 그래서
   * WHERE/ORDER BY 처럼 길이를 세어 두지 않은 부분은 {@link #WHERE_ALLOWANCE_PER_CONDITION} 같은 어림값으로 잡는다.
   *
   * <p><b>이것은 할당을 줄이는 변경이고 속도를 올리는 변경이 아니다.</b> 배치 경로에서 처음 넣을 때 측정한 대로 벽시계 시간은 잡음 안에서 오간다. 다시 손댈 일이
   * 있으면 이 숫자를 그대로 인용할 것.
   *
   * <p>{@code usingAppender} 는 mybatis 3.4.0 부터 있다(javap 으로 확인). 배치 경로는 {@code ADD_ROW()} 때문에 이미
   * 3.5.2 를 요구하고, 나머지 경로에는 이것이 새로 생기는 하한이지만 3.4.0 은 2016년 판이라 실질적인 제약이 아니다.
   */
  private static String renderPreSized(final SQL sql, final long estimatedLength) {
    // 길이를 실제로 세거나 원소 개수로 어림한 값이라 터무니없는 크기가 나올 수 없다. int 를 넘기는
    // 문장은 어차피 toString 에서 끝나므로 여기서 자르기만 한다.
    final int capacity = (int) Math.min(Math.max(estimatedLength, 16L), Integer.MAX_VALUE);
    return sql.usingAppender(new StringBuilder(capacity)).toString();
  }

  /**
   * WHERE 조건 하나가 차지하는 자리의 어림값 -- 백틱 컬럼명, 연산자, 값 리터럴, MyBatis 가 넣는 {@code "AND "} 이음말까지. 재어 본 실제 조건은
   * 11~23자라 넉넉한 쪽으로 잡았다. 크기 힌트일 뿐이므로 남는 만큼은 버퍼 여유로 남고 정확성에는 영향이 없다.
   */
  private static final int WHERE_ALLOWANCE_PER_CONDITION = 40;

  /** ORDER BY 원소 하나(백틱 컬럼명 + {@code " ASC"}/{@code " DESC"} + 이음말)의 어림값. */
  private static final int ORDER_BY_ALLOWANCE_PER_ELEMENT = 32;

  /** 키워드와 개행처럼 문장 종류마다 고정으로 붙는 부분의 어림값({@code "SELECT ... FROM ... WHERE "} 등). */
  private static final int STATEMENT_KEYWORD_ALLOWANCE = 40;

  /**
   * 매핑된 {@code @Column} 필드가 하나도 없는 엔티티를 걸러 낸다.
   *
   * <p>그런 엔티티로 컬럼이 필요한 문장을 만들면 실행되지 않는 SQL 이 조용히 나갔다 -- 전체 컬럼 SELECT 는 <b>빈 문자열</b>(MyBatis 는
   * {@code SELECT} 를 한 번도 부르지 않으면 문장 종류를 정하지 못한다), 단일 인서트는 컬럼도 {@code VALUES} 도 없는 {@code INSERT
   * INTO t}, 배치 인서트는 {@code ()} 와 {@code VALUES ()}. 어느 쪽도 데이터베이스가 받지 않는다. {@code ADD_ROW} 배치 버그,
   * {@code Map} 리터럴 따옴표 버그, {@code LIMIT}/{@code OFFSET} 버그와 같은 모양이라 같은 방식으로 끊는다.
   *
   * <p>이론적인 경우가 아니다. JPA 의 프로퍼티(게터) 접근 방식으로 {@code @Column} 을 게터에 붙인 엔티티는 이 라이브러리의 "필드에 붙은
   * {@code @Column} 만 매핑한다" 규칙 때문에 매핑 필드가 0개가 되고, 첫 조회에서 곧바로 이 상태에 빠진다.
   *
   * <p>COUNT/DELETE/UPDATE 에는 걸지 않는다. {@code SELECT COUNT(1) AS CNT FROM t} 와 {@code DELETE FROM t
   * WHERE ...} 는 엔티티 컬럼이 없어도 정상적인 문장이고, UPDATE 는 {@code updateMap} 의 키가 어차피 {@code getColumnName}
   * 에서 걸린다.
   */
  private void requireMappedColumns(final Class<?> entityClass) {
    if (entityHelper.getEntityFieldsInOrder(entityClass).isEmpty()) {
      throw new MybatisRepositoryException("entity has no @Column field: " + entityClass.getName());
    }
  }

  private static void logSql(final String label, final String rendered) {
    if (log.isDebugEnabled()) {
      log.debug("{} SQL: {}", label, rendered.replace('\n', ' '));
    }
  }

  public MybatisCommand() {
    this.entityHelper = SHARED_ENTITY_HELPER;
    this.clauseBuilder = SHARED_CLAUSE_BUILDER;
  }

  /**
   * {@code stringHelper} 는 받아만 두고 쓰지 않는다. 이 클래스가 문자열을 직접 다루는 자리는 없고 이스케이프는 모두 {@code clauseBuilder}
   * 를 거치기 때문이다. 매개변수를 지우면 이 생성자를 부르는 쪽이 깨지므로 시그니처는 그대로 둔다.
   */
  public MybatisCommand(
      MybatisEntityHelper entityHelper,
      MybatisStringHelper stringHelper,
      MybatisClauseBuilder clauseBuilder) {
    this.entityHelper = entityHelper;
    this.clauseBuilder = clauseBuilder;
  }

  // ===========================================
  // 1) COUNT
  // ===========================================
  public String buildCountSQL(ProviderContext context, Map<String, Object> whereConditions) {
    Class<?> entityClass = entityHelper.extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for count: " + context.getMapperType().getName());
    }

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL().SELECT("COUNT(1) AS CNT").FROM(tableName);
    final int conditionCount = clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);
    return renderAndLog(
        sql,
        "count",
        STATEMENT_KEYWORD_ALLOWANCE
            + tableName.length()
            + (long) conditionCount * WHERE_ALLOWANCE_PER_CONDITION);
  }

  // ===========================================
  // 2) SELECT ONE (Optional<T>)
  // ===========================================
  public String buildSelectOneSQL(ProviderContext context, Map<String, Object> whereConditions) {
    // 껍질을 벗긴 뒤에 검사한다. MyBatis 는 인자가 하나뿐인 프로바이더에 사용자가 넘긴 맵이 아니라
    // ParamMap 전체를 넘기므로, 여기서 whereConditions.isEmpty() 를 보면 언제나 false 였다.
    // 즉 이 가드는 프로바이더로 불릴 때 한 번도 걸린 적이 없고, getItemByMap(emptyMap) 은
    // WHERE 가 없는 SELECT 로 나갔다 -- 돌려받을 자리가 Optional 하나뿐인데 테이블 전체를 읽고,
    // 두 행 이상이면 TooManyResultsException 으로 끝난다.
    // update/delete 는 뒤에 ensureWhereClause 라는 두 번째 그물이 있어 살아남았지만
    // 이 경로에는 그것이 없어 이 검사가 유일한 방어선이다.
    if (clauseBuilder.extractWhereConditions(whereConditions).isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for getItemByMap");
    }
    return buildSelectSQL(context, EMPTY_SET, EMPTY_SET, whereConditions, EMPTY_LIST, null, null);
  }

  // ===========================================
  // 3) SELECT LIST
  // ===========================================
  public String buildSelectSQL(
      ProviderContext context,
      Set<String> distinctColumns,
      Set<String> targetColumns,
      Map<String, Object> whereConditions,
      List<String> orderByConditions,
      Integer limit,
      Integer offset) {
    Class<?> entityClass = entityHelper.extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for select: " + context.getMapperType().getName());
    }

    requireMappedColumns(entityClass);

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL();
    // SELECT 절
    final int selectLength =
        clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns, entityClass);
    sql.FROM(tableName);

    // WHERE 절
    final int conditionCount = clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // ORDER BY 절
    clauseBuilder.appendOrderBy(sql, orderByConditions, entityClass);

    // LIMIT / OFFSET
    // 두 값을 그대로 흘려보내면 데이터베이스가 받지 않는 문장이 조용히 만들어진다. ADD_ROW 이전의
    // 배치 인서트, 그리고 Map 리터럴의 따옴표와 같은 모양의 문제다 -- 실행해 보기 전에는 아무도 모른다.
    //   getItemsLimitOffset(null, 20) -> "... FROM t OFFSET 20"
    //   MySQL/MariaDB 의 OFFSET 은 LIMIT 의 일부라서 혼자서는 문법 오류다.
    //   getItemsLimitOffset(-5, -1)   -> "... LIMIT -5 OFFSET -1" 역시 문법 오류.
    // 어느 쪽도 조용히 고쳐 줄 올바른 해석이 없으므로 다른 입력 검증과 같은 자리에서 끊는다.
    // 0 은 막지 않는다 -- LIMIT 0 도 OFFSET 0 도 정상적인 문장이다.
    if (limit != null && limit < 0) {
      throw new MybatisRepositoryException("limit must not be negative: " + limit);
    }
    if (offset != null && offset < 0) {
      throw new MybatisRepositoryException("offset must not be negative: " + offset);
    }
    if (offset != null && limit == null) {
      throw new MybatisRepositoryException("offset requires limit: offset=" + offset);
    }
    if (limit != null) {
      sql.LIMIT(limit);
    }
    if (offset != null) {
      sql.OFFSET(offset);
    }

    return renderAndLog(
        sql,
        "select",
        STATEMENT_KEYWORD_ALLOWANCE
            + selectLength
            + tableName.length()
            + (long) conditionCount * WHERE_ALLOWANCE_PER_CONDITION
            + (orderByConditions == null
                ? 0L
                : (long) orderByConditions.size() * ORDER_BY_ALLOWANCE_PER_ELEMENT)
            + (limit == null ? 0L : 24L)
            + (offset == null ? 0L : 24L));
  }

  // ===========================================
  // 4) INSERT ONE
  // ===========================================
  public <T> String buildInsertSQL(T entity) {
    if (entity == null) {
      throw new MybatisRepositoryException("entity is null for insert");
    }

    final Class<?> entityClass = entity.getClass();
    requireMappedColumns(entityClass);

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL().INSERT_INTO(tableName);

    // 배치 인서트와 같은 Field 목록에서 값을 바로 읽는다. 예전에는 toMap 으로 HashMap 을 만든 뒤
    // 그 entrySet 을 돌았는데, 필요한 것은 (컬럼, 값) 짝뿐이라 인서트마다 Map 하나와 엔트리 N개가
    // 통째로 쓰레기가 됐다. ORDERED_FIELD_CACHE 의 순서는 toMap 이 만들던 HashMap 의 버킷
    // 순서와 같으므로(둘 다 같은 키를 같은 순서로 담은 기본 용량 해시 컨테이너다) 생성되는
    // 문장도 그대로다 -- MybatisSqlGenerationTest 가 4컬럼과 12컬럼 문장을 통째로 박아 두어 지킨다.
    // 컬럼명도 같은 순서로 캐시해 두고 인덱스로 짝지어 보았지만(중첩 맵 조회 2회 → 목록 읽기 1회)
    // 20컬럼 인서트에서 오히려 3~4% 느렸다. getWrappedColumnName 은 이미 캐시된 조회라 아낄 것이
    // 없고, 값과 어긋나면 다른 컬럼에 쓰는 정렬 불변식만 하나 더 생긴다. 재보지 않고 되돌리지 말 것.
    // 완성될 문장의 길이를 컬럼과 값을 넘기면서 함께 세어 둔다. 아래 렌더링에 쓸 크기 힌트다.
    long renderedLength = STATEMENT_KEYWORD_ALLOWANCE + tableName.length();
    for (Field field : entityHelper.getEntityFieldsInOrder(entityClass)) {
      final Object value;
      try {
        // setAccessible 은 캐시에 담을 때 이미 끝냈다.
        value = field.get(entity);
      } catch (Exception e) {
        // toMap 이 읽지 못한 필드를 Map 에 담지 않아 그 컬럼이 문장에서 통째로 빠지던 것과 맞춘다.
        log.warn("Failed to get field value for {}: {}", field.getName(), e.getMessage());
        log.debug("Stack trace: ", e);
        continue;
      }
      // 두 문자열을 지역 변수로 받는 것은 길이를 세기 위한 것뿐이다. 컬럼명을 목록에 담아 두고
      // 인덱스로 값과 짝지으려던 (측정해서 되돌린) 시도와는 다르다 -- 여기서는 짝이 한 줄 안에 있다.
      final String column = entityHelper.getWrappedColumnName(entityClass, field.getName());
      // 값이 null 이면 "정하지 않았다" 는 뜻이므로 DEFAULT 로 자리만 채워 DB 기본값을 받게 한다.
      final String formatted =
          value == null ? DEFAULT_KEYWORD : clauseBuilder.formatValueForSQL(value);
      // 컬럼 목록과 값 목록에 각각 ", " 가 하나씩 붙는다.
      renderedLength += column.length() + formatted.length() + 4L;
      sql.VALUES(column, formatted);
    }

    return renderAndLog(sql, "insert", renderedLength);
  }

  // ===========================================
  // 5) INSERT BATCH
  // ===========================================
  public <T> String buildInsertBatchSQL(List<T> entities) {
    if (entities == null || entities.isEmpty()) {
      throw new MybatisRepositoryException("entities empty for insertBatch");
    }

    final Class<?> expectedType = requireSingleEntityType(entities);
    requireMappedColumns(expectedType);

    String tableName = entityHelper.getTableName(expectedType);

    // INSERT INTO table (col1, col2, …)
    // 테이블명은 나머지 다섯 경로(count/select/insert/update/delete)와 똑같이 감싸지 않는다.
    // 여기만 wrapIdentifier 를 거치면 컬럼용 규칙이 테이블명에 적용되어, @Table(name="shop.orders")
    // 처럼 스키마를 붙인 이름이나 차단 목록에 걸리는 이름이 배치 인서트에서만 예외가 났다.
    //
    // 컬럼 목록은 배치마다 스트림으로 다시 이어 붙이고 있었다. 전체 컬럼 SELECT 가 쓰는 캐시와
    // 같은 집합을 같은 순서로 같은 구분자(", ")로 잇는 것이라 결과 문자열이 글자까지 같다.
    SQL sql = new SQL().INSERT_INTO(tableName);
    sql.INTO_COLUMNS(entityHelper.getSelectColumnList(expectedType));

    // VALUES ( … ), ( … ), …
    // MyBatis 의 insertSQL 은 INTO_VALUES 로 넣어 둔 목록을 통째로 괄호로 한 번 감싼다. 그래서 행마다
    // "(...)" 를 직접 붙여 한 문자열로 넘기면 VALUES ((...), (...)) 가 되어 실행되지 않는 문장이 된다.
    // 행 구분은 ADD_ROW() 로 하고 괄호는 MyBatis 가 붙이게 둔다.
    // 값은 컬럼 순서대로만 꺼내 쓰므로 행마다 toMap 으로 Map 을 만들 이유가 없다.
    // 그 순서에 맞춰 둔 Field 목록에서 곧바로 읽는다(1000행 x 20컬럼이면 Map 1000개가 사라진다).
    final List<Field> orderedFields = entityHelper.getEntityFieldsInOrder(expectedType);
    final StringBuilder row = new StringBuilder(orderedFields.size() * 16);
    // 완성될 문장의 길이를 행을 만들면서 함께 세어 둔다. 아래 렌더링에 쓸 크기 힌트다.
    long renderedLength = 64L + tableName.length();
    boolean firstRow = true;
    for (T entity : entities) {
      if (!firstRow) {
        sql.ADD_ROW();
      }
      firstRow = false;
      row.setLength(0);
      boolean firstColumn = true;
      for (Field field : orderedFields) {
        if (!firstColumn) {
          row.append(", ");
        }
        firstColumn = false;
        // 단건 INSERT 와 같은 규약이다. 값이 null 인 자리에는 DEFAULT 를 넣어 DB 기본값을 받게 한다.
        // 행마다 null 인 필드가 달라도 컬럼 목록은 하나로 고정되므로(자리만 바뀐다) 배치가 성립한다.
        final Object value = readFieldValue(field, entity);
        row.append(value == null ? DEFAULT_KEYWORD : clauseBuilder.formatValueForSQL(value));
      }
      // 행 하나가 차지하는 자리: 값들 + 감싸는 괄호 둘 + 행 구분자 "\n, ".
      renderedLength += row.length() + 8L;
      sql.INTO_VALUES(row.toString());
    }

    return renderAndLog(sql, "insertBatch", renderedLength);
  }

  /**
   * 배치의 모든 원소가 null 이 아니고 같은 타입인지 확인하고 그 타입을 돌려준다.
   *
   * <p>예전에는 {@code entities.get(0).getClass()} 를 먼저 불렀기 때문에, 첫 원소가 null 이면 아래 메시지 대신 맨 NPE 가 나갔다.
   */
  private static <T> Class<?> requireSingleEntityType(final List<T> entities) {
    Class<?> expectedType = null;
    for (T entity : entities) {
      if (entity == null) {
        throw new MybatisRepositoryException("entity cannot be null in batch insert");
      }
      if (expectedType == null) {
        expectedType = entity.getClass();
      } else if (!entity.getClass().equals(expectedType)) {
        throw new MybatisRepositoryException(
            String.format(
                "All entities must be of the same type. Expected: %s, Found: %s",
                expectedType.getName(), entity.getClass().getName()));
      }
    }
    return expectedType;
  }

  // ===========================================
  // 6) UPDATE
  // ===========================================
  public String buildUpdateSQL(
      ProviderContext context, Map<String, Object> updateMap, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for update");
    }
    // SET 절이 하나도 없으면 MyBatis 는 "UPDATE t WHERE (...)" 를 만든다. SET 이 빠진 문장은 어느 DB 도
    // 받지 않으므로 DB 까지 보내 문법 오류를 받을 이유가 없다. null 은 여기까지 오면 아래 entrySet()
    // 에서 맨 NPE 가 나가던 자리라, 다른 입력 검증과 같은 예외로 맞춘다.
    // 값이 null 인 키는 여기서 걸러지지 않는다 -- 맵 경로에서 키가 있으면 `컬럼` = null 로 나가는 것이 규약이다.
    // 다만 엔티티 경로(updateById/updateByMap)는 toNonNullMap 이 null 필드를 미리 뺀 맵을 넘기므로,
    // 전 필드가 null 인 엔티티는 빈 맵이 되어 여기에 걸린다. 기본 메시지만으로는 누가 비웠는지 알 수 없어
    // 두 경로를 함께 짚어 준다.
    if (updateMap == null || updateMap.isEmpty()) {
      throw new MybatisRepositoryException(
          "'updateMap' is required for update"
              + " (SET 할 컬럼이 없다."
              + " 엔티티 경로는 값이 null 인 필드를 SET 에서 빼므로 전 필드가 null 인 엔티티는 여기에 걸린다."
              + " 컬럼을 NULL 로 비우려면 updateMapById/updateMapByMap 에 null 값을 담아 넘길 것)");
    }
    Class<?> entityClass = entityHelper.extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for update: " + context.getMapperType().getName());
    }

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL().UPDATE(tableName);

    // SET 절의 길이는 세어 둔다. WHERE 는 붙은 개수만 알 수 있어 어림값으로 잡는다.
    long renderedLength = STATEMENT_KEYWORD_ALLOWANCE + tableName.length();
    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      String fieldName = entry.getKey();
      String columnName = entityHelper.getColumnName(entityClass, fieldName);
      final String assignment = clauseBuilder.buildEqualClause(columnName, entry.getValue());
      renderedLength += assignment.length() + 2L;
      sql.SET(assignment);
    }
    final int conditionCount = clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // WHERE 없는 UPDATE 를 로그에 완성된 문장으로 먼저 흘리지 않도록 가드를 통과한 뒤에 찍는다.
    clauseBuilder.ensureWhereClause(conditionCount);
    return renderAndLog(
        sql, "update", renderedLength + (long) conditionCount * WHERE_ALLOWANCE_PER_CONDITION);
  }

  // ===========================================
  // 7) DELETE
  // ===========================================
  public String buildDeleteSQL(ProviderContext context, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for delete");
    }
    Class<?> entityClass = entityHelper.extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for delete: " + context.getMapperType().getName());
    }

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL().DELETE_FROM(tableName);
    final int conditionCount = clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // WHERE 없는 DELETE 를 로그에 완성된 문장으로 먼저 흘리지 않도록 가드를 통과한 뒤에 찍는다.
    clauseBuilder.ensureWhereClause(conditionCount);
    return renderAndLog(
        sql,
        "delete",
        STATEMENT_KEYWORD_ALLOWANCE
            + tableName.length()
            + (long) conditionCount * WHERE_ALLOWANCE_PER_CONDITION);
  }

  // ===========================================
  // Utility: 객체 → Map<String,Object>
  // (이제 entityClass를 넘겨 받아서 필터링 처리)
  // ===========================================
  /**
   * 필드 값을 읽되 실패하면 {@code null} 로 떨어뜨린다. {@code toMap} 이 실패한 필드를 Map 에 담지 않아 조회 시 {@code null} 이 되던
   * 것과 결과가 같다.
   */
  private static Object readFieldValue(final Field field, final Object source) {
    try {
      // setAccessible 은 캐시에 담을 때 이미 끝냈고 Field#get 은 Field 를 건드리지 않는다.
      return field.get(source);
    } catch (Exception e) {
      log.warn("Failed to get field value for {}: {}", field.getName(), e.getMessage());
      log.debug("Stack trace: ", e);
      return null;
    }
  }

  public static Map<String, Object> toMap(final Object source) {
    if (source == null) {
      throw new MybatisRepositoryException("Source object cannot be null");
    }

    List<Field> fields = MybatisEntityHelper.getAllNonExcludedFields(source.getClass());
    // 크기를 미리 지정하면 안 된다. buildInsertSQL 과 (updateById 경로의) buildUpdateSQL 이 이 맵의
    // entrySet 을 그대로 순회하므로, 버킷 순서가 곧 INSERT 컬럼 순서이자 UPDATE SET 절 순서다.
    // 용량이 달라지면 그 순서가 바뀐다(@Column 3, 4, 5, 12, 24개 등에서 확인).
    // 리해시 한 번을 아끼자고 생성되는 SQL 을 바꿀 이유는 없다.
    Map<String, Object> map = new HashMap<>();

    for (Field field : fields) {
      try {
        // setAccessible 은 캐시에 담을 때 이미 끝냈고 Field#get 은 Field 를 건드리지 않는다.
        // 여기서 잠그면 캐시가 공유하는 Field 하나를 두고 모든 스레드가 줄을 서게 된다(배치 인서트에서 특히).
        Object val = field.get(source);
        map.put(field.getName(), val);
      } catch (Exception e) {
        log.warn("Failed to get field value for {}: {}", field.getName(), e.getMessage());
        log.debug("Stack trace: ", e);
      }
    }
    return map;
  }

  /**
   * {@link #toMap(Object)} 에서 값이 {@code null} 인 항목을 뺀 맵. 엔티티 기반 UPDATE 가 쓰는 입구다.
   *
   * <p>엔티티 경로에서 {@code null} 은 "이 컬럼은 정하지 않았다" 는 뜻이므로 SET 절에 나오면 안 된다. 예전에는 {@code toMap} 을 그대로 넘겨
   * 엔티티에 채우지 않은 컬럼까지 전부 {@code = null} 로 덮었다. 컬럼을 정말 비우고 싶으면 맵 경로({@code updateMapById} / {@code
   * updateMapByMap})에 {@code null} 값을 담아 넘긴다 -- 그쪽 규약은 그대로다.
   *
   * <p>{@code LinkedHashMap} 에 {@code toMap} 의 순회 순서 그대로 담으므로 SET 절 순서가 {@code toMap} 순서(= INSERT
   * 컬럼 순서)와 어긋나지 않는다.
   *
   * <p>{@code toMap} 은 공개 API 라 그대로 둔다. 소비자가 쓰고 있다.
   *
   * <p>결과가 비면(=엔티티의 모든 필드가 null) 여기서 끊지 않고 그대로 넘긴다. {@code buildUpdateSQL} 의 빈 updateMap 가드가 {@code
   * whereConditions} 검사 뒤에 오도록 순서가 고정되어 있어서다.
   */
  public static Map<String, Object> toNonNullMap(final Object source) {
    final Map<String, Object> map = toMap(source);
    final Map<String, Object> nonNull = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (entry.getValue() != null) {
        nonNull.put(entry.getKey(), entry.getValue());
      }
    }
    return nonNull;
  }
}
