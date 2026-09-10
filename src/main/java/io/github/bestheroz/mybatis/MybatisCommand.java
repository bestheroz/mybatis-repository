package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

  protected static final Set<String> METHOD_LIST =
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

  private final MybatisEntityHelper entityHelper;
  private final MybatisStringHelper stringHelper;
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
    this.stringHelper = SHARED_STRING_HELPER;
    this.entityHelper = SHARED_ENTITY_HELPER;
    this.clauseBuilder = SHARED_CLAUSE_BUILDER;
  }

  public MybatisCommand(
      MybatisEntityHelper entityHelper,
      MybatisStringHelper stringHelper,
      MybatisClauseBuilder clauseBuilder) {
    this.entityHelper = entityHelper;
    this.stringHelper = stringHelper;
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

    String tableName = entityHelper.getTableName(entity.getClass());
    SQL sql = new SQL().INSERT_INTO(tableName);

    Map<String, Object> entityMap = toMap(entity);
    for (Map.Entry<String, Object> entry : entityMap.entrySet()) {
      String columnName = entityHelper.getColumnName(entity.getClass(), entry.getKey());
      sql.VALUES(
          stringHelper.wrapIdentifier(columnName),
          clauseBuilder.formatValueForSQL(entry.getValue()));
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

    // Check for null entities and type consistency
    Class<?> expectedType = entities.get(0).getClass();
    for (T entity : entities) {
      if (entity == null) {
        throw new MybatisRepositoryException("entity cannot be null in batch insert");
      }
      if (!entity.getClass().equals(expectedType)) {
        throw new MybatisRepositoryException(
            String.format(
                "All entities must be of the same type. Expected: %s, Found: %s",
                expectedType.getName(), entity.getClass().getName()));
      }
    }

    String tableName = entityHelper.getTableName(expectedType);
    Set<String> columns = entityHelper.getEntityFields(expectedType);

    // INSERT INTO table (col1, col2, …)
    String wrappedTable = stringHelper.wrapIdentifier(tableName);
    SQL sql = new SQL().INSERT_INTO(wrappedTable);
    sql.INTO_COLUMNS(
        columns.stream()
            .map(v -> entityHelper.getColumnName(expectedType, v))
            .map(stringHelper::wrapIdentifier)
            .collect(Collectors.joining(", ")));

    // VALUES ( … ), ( … ), …
    // 행마다 List 를 만들어 모아 두었다가 다시 잇지 않고 곧바로 이어 붙인다.
    // 1000행 x 20컬럼 배치라면 리스트 1000개와 중간 문자열 2만 개가 통째로 사라진다.
    // 곱을 int 로 계산하면 셀이 약 1.3억 개를 넘을 때 음수로 뒤집혀 NegativeArraySizeException 이 난다.
    // 어차피 초기 크기 힌트일 뿐이므로 long 으로 계산해 적당한 상한에서 자른다.
    final int sizeHint = (int) Math.min((long) entities.size() * columns.size() * 16L, 1L << 20);
    final StringBuilder values = new StringBuilder(sizeHint);
    boolean firstRow = true;
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      if (!firstRow) {
        values.append(", ");
      }
      firstRow = false;
      values.append('(');
      boolean firstColumn = true;
      for (String fieldName : columns) {
        if (!firstColumn) {
          values.append(", ");
        }
        firstColumn = false;
        values.append(clauseBuilder.formatValueForSQL(entityMap.get(fieldName)));
      }
      values.append(')');
    }
    sql.INTO_VALUES(values.toString());

    return renderAndLog(sql, "insertBatch");
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
    clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // WHERE 없는 UPDATE 를 로그에 완성된 문장으로 먼저 흘리지 않도록 가드를 통과한 뒤에 찍는다.
    final String rendered = sql.toString();
    clauseBuilder.ensureWhereClause(rendered);
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
    clauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // WHERE 없는 DELETE 를 로그에 완성된 문장으로 먼저 흘리지 않도록 가드를 통과한 뒤에 찍는다.
    final String rendered = sql.toString();
    clauseBuilder.ensureWhereClause(rendered);
    logSql("delete", rendered);
    return rendered;
  }

  // ===========================================
  // Utility: 객체 → Map<String,Object>
  // (이제 entityClass를 넘겨 받아서 필터링 처리)
  // ===========================================
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
