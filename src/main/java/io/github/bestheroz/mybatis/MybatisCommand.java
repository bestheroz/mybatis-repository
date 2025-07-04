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
    log.debug("count SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
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

    log.debug("select SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
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

    log.debug("insert SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
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
    List<List<String>> valuesList = new ArrayList<>();
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      List<String> rowValues = new ArrayList<>();
      for (String fieldName : columns) {
        rowValues.add(clauseBuilder.formatValueForSQL(entityMap.get(fieldName)));
      }
      valuesList.add(rowValues);
    }
    String valuesJoined =
        valuesList.stream()
            .map(row -> "(" + String.join(", ", row) + ")")
            .collect(Collectors.joining(", "));
    sql.INTO_VALUES(valuesJoined);

    log.debug("insertBatch SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
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
    clauseBuilder.ensureWhereClause(sql);

    log.debug("update SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
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
    clauseBuilder.ensureWhereClause(sql);

    log.debug("delete SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // Utility: 객체 → Map<String,Object>
  // (이제 entityClass를 넘겨 받아서 필터링 처리)
  // ===========================================
  public static Map<String, Object> toMap(final Object source) {
    if (source == null) {
      throw new MybatisRepositoryException("Source object cannot be null");
    }

    Map<String, Object> map = new HashMap<>();
    List<Field> fields = MybatisEntityHelper.getAllNonExcludedFields(source.getClass());

    for (Field field : fields) {
      try {
        // Thread-safe field access
        synchronized (field) {
          field.setAccessible(true);
          Object val = field.get(source);
          map.put(field.getName(), val);
        }
      } catch (Exception e) {
        log.warn("Failed to get field value for {}: {}", field.getName(), e.getMessage());
        log.debug("Stack trace: ", e);
      }
    }
    return map;
  }
}
