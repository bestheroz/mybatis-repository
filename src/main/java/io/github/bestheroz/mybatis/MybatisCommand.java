package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
  // Allowed Method List
  // ======================
  public static final String SELECT_ITEMS = "getDistinctAndTargetItemsByMapOrderByLimitOffset";
  public static final String SELECT_ITEM_BY_MAP = "getItemByMap";
  public static final String COUNT_BY_MAP = "countByMap";
  public static final String INSERT = "insert";
  public static final String INSERT_BATCH = "insertBatch";
  public static final String UPDATE_MAP_BY_MAP = "updateMapByMap";
  public static final String DELETE_BY_MAP = "deleteByMap";
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

  // 기본 생성자 제거하고 의존성 주입을 위한 생성자만 남김
  public MybatisCommand(
      MybatisEntityHelper entityHelper,
      MybatisStringHelper stringHelper,
      MybatisClauseBuilder clauseBuilder) {
    this.entityHelper = entityHelper;
    this.stringHelper = stringHelper;
    this.clauseBuilder = clauseBuilder;
  }

  // ===========================================
  // SELECT
  // ===========================================
  public String countByMap(final Map<String, Object> whereConditions) {
    SQL sql = new SQL().SELECT("COUNT(1) AS CNT").FROM(entityHelper.getTableName());
    clauseBuilder.buildWhereClause(sql, whereConditions);
    log.debug("count SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  public String getItemByMap(final Map<String, Object> whereConditions) {
    clauseBuilder.validateWhereConditions(whereConditions);
    return getDistinctAndTargetItemsByMapOrderByLimitOffset(
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  public String getDistinctAndTargetItemsByMapOrderByLimitOffset(
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {

    SQL sql = new SQL();

    // SELECT
    clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns);
    sql.FROM(entityHelper.getTableName());

    // WHERE
    clauseBuilder.buildWhereClause(sql, whereConditions);

    // ORDER BY
    clauseBuilder.appendOrderBy(sql, orderByConditions);

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
  // INSERT
  // ===========================================
  public <T> String insert(final T entity) {
    SQL sql = new SQL();
    sql.INSERT_INTO(entityHelper.getTableName(entity.getClass()));

    Map<String, Object> entityMap = toMap(entity);
    for (Map.Entry<String, Object> entry : entityMap.entrySet()) {
      sql.VALUES(
          stringHelper.wrapIdentifier(entityHelper.getColumnName(entry.getKey())),
          clauseBuilder.formatValueForSQL(entry.getValue()));
    }

    log.debug("insert SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  public <T> String insertBatch(final List<T> entities) {
    if (entities.isEmpty()) {
      log.warn("entities are empty");
      throw new MybatisRepositoryException("entities empty");
    }

    SQL sql = new SQL();
    Class<?> entityClass = entities.get(0).getClass();
    String tableName = stringHelper.wrapIdentifier(entityHelper.getTableName(entityClass));
    sql.INSERT_INTO(tableName);

    // 전체 컬럼 (Exclude 제외)
    Set<String> columns = entityHelper.getEntityFields(entityClass);
    List<String> columnList =
        columns.stream().map(entityHelper::getColumnName).collect(Collectors.toList());

    // INSERT INTO table (col1, col2, ...)
    String columnsJoined =
        columnList.stream().map(stringHelper::wrapIdentifier).collect(Collectors.joining(", "));
    sql.INTO_COLUMNS(columnsJoined);

    // VALUES ( ... ), ( ... ), ...
    List<List<String>> valuesList = new ArrayList<>();
    for (T entity : entities) {
      Map<String, Object> entityMap = toMap(entity);
      List<String> rowValueList = new ArrayList<>();
      for (String column : columns) {
        rowValueList.add(clauseBuilder.formatValueForSQL(entityMap.get(column)));
      }
      valuesList.add(rowValueList);
    }
    String valuesJoined =
        valuesList.stream()
            .map(value -> String.join(", ", value))
            .collect(Collectors.joining("), ("));
    sql.INTO_VALUES(valuesJoined);

    log.debug("insertBatch SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // UPDATE
  // ===========================================
  public String updateMapByMap(
      final Map<String, Object> updateMap, final Map<String, Object> whereConditions) {
    clauseBuilder.validateWhereConditions(whereConditions);

    SQL sql = new SQL().UPDATE(entityHelper.getTableName());
    for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
      final String javaFieldName = entry.getKey();
      final Object value = entry.getValue();

      // ExcludeFields 제외
      if (!MybatisProperties.getExcludeFields().contains(javaFieldName)) {
        String dbColumnName = entityHelper.getColumnName(javaFieldName);
        sql.SET(clauseBuilder.buildEqualClause(dbColumnName, value));
      }
    }

    clauseBuilder.buildWhereClause(sql, whereConditions);
    clauseBuilder.ensureWhereClause(sql);

    log.debug("update SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  // ===========================================
  // DELETE
  // ===========================================
  public String deleteByMap(final Map<String, Object> whereConditions) {
    clauseBuilder.validateWhereConditions(whereConditions);

    SQL sql = new SQL().DELETE_FROM(entityHelper.getTableName());
    clauseBuilder.buildWhereClause(sql, whereConditions);
    clauseBuilder.ensureWhereClause(sql);

    log.debug("delete SQL: {}", sql.toString().replaceAll("\n", " "));
    return sql.toString();
  }

  /** Reflection을 통해 객체의 필드를 Map 으로 변환 */
  public static Map<String, Object> toMap(final Object source) {
    final Map<String, Object> map = new HashMap<>();
    final Field[] fields = MybatisEntityHelper.getAllNonExcludedFields(source.getClass());
    for (final Field field : fields) {
      field.setAccessible(true);
      try {
        map.put(field.getName(), field.get(source));
      } catch (final Exception e) {
        log.warn(MybatisStringHelper.getStackTrace(e));
      }
    }
    return map;
  }
}
