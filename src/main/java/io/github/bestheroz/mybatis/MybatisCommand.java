package io.github.bestheroz.mybatis;

import static io.github.bestheroz.mybatis.MybatisCommand.*;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
  // Caches
  // ======================
  protected static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();
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

  public MybatisCommand() {
    this.stringHelper = new MybatisStringHelper();
    this.entityHelper = new MybatisEntityHelper(stringHelper);
    this.clauseBuilder = new MybatisClauseBuilder(stringHelper, entityHelper);
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
  public static String buildCountSQL(ProviderContext context, Map<String, Object> whereConditions) {
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for count: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    SQL sql = new SQL().SELECT("COUNT(1) AS CNT").FROM(tableName);
    staticClauseBuilder.buildWhereClause(sql, whereConditions, entityClass);
    log.debug("count SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // 2) SELECT ONE (Optional<T>)
  // ===========================================
  public static String buildSelectOneSQL(
      ProviderContext context, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for getItemByMap");
    }
    return buildSelectSQL(
        context,
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  // ===========================================
  // 3) SELECT LIST
  // ===========================================
  public static String buildSelectSQL(
      ProviderContext context,
      Set<String> distinctColumns,
      Set<String> targetColumns,
      Map<String, Object> whereConditions,
      List<String> orderByConditions,
      Integer limit,
      Integer offset) {
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for select: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    SQL sql = new SQL();
    // SELECT 절
    staticClauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns, entityClass);
    sql.FROM(tableName);

    // WHERE 절
    staticClauseBuilder.buildWhereClause(sql, whereConditions, entityClass);

    // ORDER BY 절
    staticClauseBuilder.appendOrderBy(sql, orderByConditions, entityClass);

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
  public static <T> String buildInsertSQL(ProviderContext context, T entity) {
    if (entity == null) {
      throw new MybatisRepositoryException("entity is null for insert");
    }
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for insert: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    SQL sql = new SQL().INSERT_INTO(tableName);

    Map<String, Object> entityMap = toMap(entity);
    for (Map.Entry<String, Object> entry : entityMap.entrySet()) {
      String columnName = entityHelperStatic.getColumnName(entityClass, entry.getKey());
      sql.VALUES(
          stringHelperStatic.wrapIdentifier(columnName),
          staticClauseBuilder.formatValueForSQL(entry.getValue()));
    }

    log.debug("insert SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // 5) INSERT BATCH
  // ===========================================
  public static <T> String buildInsertBatchSQL(ProviderContext context, List<T> entities) {
    if (entities == null || entities.isEmpty()) {
      throw new MybatisRepositoryException("entities empty for insertBatch");
    }
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for insertBatch: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    Set<String> columns = entityHelperStatic.getEntityFields(entityClass);

    // INSERT INTO table (col1, col2, …)
    String wrappedTable = stringHelperStatic.wrapIdentifier(tableName);
    SQL sql = new SQL().INSERT_INTO(wrappedTable);
    String columnsJoined =
        columns.stream()
            .map(v -> entityHelperStatic.getColumnName(entityClass, v))
            .map(stringHelperStatic::wrapIdentifier)
            .collect(Collectors.joining(", "));
    sql.INTO_COLUMNS(columnsJoined);

    // VALUES ( … ), ( … ), …
    List<List<String>> valuesList = new ArrayList<>();
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      List<String> rowValues = new ArrayList<>();
      for (String fieldName : columns) {
        rowValues.add(staticClauseBuilder.formatValueForSQL(entityMap.get(fieldName)));
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
  public static String buildUpdateSQL(
      ProviderContext context, Map<String, Object> updateMap, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for update");
    }
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for update: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    SQL sql = new SQL().UPDATE(tableName);

    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      String fieldName = entry.getKey();
      if (!MybatisProperties.getExcludeFields().contains(fieldName)) {
        String columnName = entityHelperStatic.getColumnName(entityClass, fieldName);
        sql.SET(staticClauseBuilder.buildEqualClause(columnName, entry.getValue()));
      }
    }
    staticClauseBuilder.buildWhereClause(sql, whereConditions, entityClass);
    staticClauseBuilder.ensureWhereClause(sql);

    log.debug("update SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // 7) DELETE
  // ===========================================
  public static String buildDeleteSQL(
      ProviderContext context, Map<String, Object> whereConditions) {
    if (whereConditions == null || whereConditions.isEmpty()) {
      throw new MybatisRepositoryException("'where' Conditions is required for delete");
    }
    Class<?> entityClass = extractEntityClassFromMapper(context.getMapperType());
    if (entityClass == null) {
      throw new MybatisRepositoryException(
          "cannot determine entity class for delete: " + context.getMapperType().getName());
    }

    String tableName = entityHelperStatic.getTableName(entityClass);
    SQL sql = new SQL().DELETE_FROM(tableName);
    staticClauseBuilder.buildWhereClause(sql, whereConditions, entityClass);
    staticClauseBuilder.ensureWhereClause(sql);

    log.debug("delete SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // Utility: Mapper 인터페이스 → 엔티티 클래스
  // ===========================================
  @SuppressWarnings("unchecked")
  private static <E> Class<E> extractEntityClassFromMapper(Class<?> mapperInterface) {
    // (1) mapperInterface가 implements한 Generic Interfaces 확인
    Type[] genericIfs = mapperInterface.getGenericInterfaces();
    for (Type t : genericIfs) {
      if (t instanceof ParameterizedType) {
        ParameterizedType pt = (ParameterizedType) t;
        if (pt.getRawType() == MybatisRepository.class) {
          Type actual = pt.getActualTypeArguments()[0];
          if (actual instanceof Class) {
            return (Class<E>) actual;
          }
        }
      }
    }
    // (2) 부모 인터페이스 재귀 탐색
    for (Class<?> parentIf : mapperInterface.getInterfaces()) {
      Class<E> found = extractEntityClassFromMapper(parentIf);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  // ===========================================
  // Utility: 객체 → Map<String,Object>
  // (이제 entityClass를 넘겨 받아서 필터링 처리)
  // ===========================================
  public static Map<String, Object> toMap(final Object source) {
    Map<String, Object> map = new HashMap<>();
    Field[] fields = MybatisEntityHelper.getAllNonExcludedFields(source.getClass());
    for (Field field : fields) {
      field.setAccessible(true);
      try {
        Object val = field.get(source);
        map.put(field.getName(), val);
      } catch (Exception e) {
        log.warn(MybatisStringHelper.getStackTrace(e));
      }
    }
    return map;
  }

  // ===========================================
  // static 접근용 헬퍼 인스턴스 (ProviderContext가 static 메서드를 호출하기 위해)
  // ===========================================
  private static final MybatisStringHelper stringHelperStatic = new MybatisStringHelper();
  private static final MybatisEntityHelper entityHelperStatic =
      new MybatisEntityHelper(stringHelperStatic);
  private static final MybatisClauseBuilder staticClauseBuilder =
      new MybatisClauseBuilder(stringHelperStatic, entityHelperStatic);
}
