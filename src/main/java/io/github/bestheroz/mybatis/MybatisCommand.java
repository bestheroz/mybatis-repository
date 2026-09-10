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
   * SQL 을 한 번만 문자열로 만들고, 디버그 로그가 켜져 있을 때만 개행을 지운 사본을 만든다.
   *
   * <p>{@code log.debug("{}", sql.toString().replaceAll(...))} 는 로그가 꺼져 있어도 인자를 먼저 계산한다. 즉 레벨과 무관하게
   * SQL 전체 사본과 정규식 Pattern 이 매번 만들어졌다. 배치 인서트처럼 SQL 이 큰 경우 그대로 낭비다.
   */
  private static String renderAndLog(final SQL sql, final String label) {
    final String rendered = sql.toString();
    logSql(label, rendered);
    return rendered;
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
    clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);
    return renderAndLog(sql, "count");
  }

  // ===========================================
  // 2) SELECT ONE (Optional<T>)
  // ===========================================
  public String buildSelectOneSQL(ProviderContext context, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
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

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL();
    // SELECT 절
    clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns, entityClass);
    sql.FROM(tableName);

    // WHERE 절
    clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // ORDER BY 절
    clauseBuilder.appendOrderBy(sql, orderByConditions, entityClass);

    // LIMIT / OFFSET
    if (limit != null) {
      sql.LIMIT(limit);
    }
    if (offset != null) {
      sql.OFFSET(offset);
    }

    return renderAndLog(sql, "select");
  }

  // ===========================================
  // 4) INSERT ONE
  // ===========================================
  public <T> String buildInsertSQL(T entity) {
    if (entity == null) {
      throw new MybatisRepositoryException("entity is null for insert");
    }

    final Class<?> entityClass = entity.getClass();
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
      sql.VALUES(
          entityHelper.getWrappedColumnName(entityClass, field.getName()),
          clauseBuilder.formatValueForSQL(value));
    }

    return renderAndLog(sql, "insert");
  }

  // ===========================================
  // 5) INSERT BATCH
  // ===========================================
  public <T> String buildInsertBatchSQL(List<T> entities) {
    if (entities == null || entities.isEmpty()) {
      throw new MybatisRepositoryException("entities empty for insertBatch");
    }

    final Class<?> expectedType = requireSingleEntityType(entities);

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
        row.append(clauseBuilder.formatValueForSQL(readFieldValue(field, entity)));
      }
      sql.INTO_VALUES(row.toString());
    }

    return renderAndLog(sql, "insertBatch");
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
    Class<?> entityClass = entityHelper.extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for update: " + context.getMapperType().getName());
    }

    String tableName = entityHelper.getTableName(entityClass);
    SQL sql = new SQL().UPDATE(tableName);

    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      String fieldName = entry.getKey();
      String columnName = entityHelper.getColumnName(entityClass, fieldName);
      sql.SET(clauseBuilder.buildEqualClause(columnName, entry.getValue()));
    }
    final int conditionCount = clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // WHERE 없는 UPDATE 를 로그에 완성된 문장으로 먼저 흘리지 않도록 가드를 통과한 뒤에 찍는다.
    clauseBuilder.ensureWhereClause(conditionCount);
    final String rendered = sql.toString();
    logSql("update", rendered);
    return rendered;
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
    final String rendered = sql.toString();
    logSql("delete", rendered);
    return rendered;
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
}
