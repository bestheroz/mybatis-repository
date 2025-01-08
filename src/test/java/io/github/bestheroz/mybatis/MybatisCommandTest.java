package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.util.*;
import org.apache.ibatis.jdbc.SQL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisCommandTest {

  @Mock private MybatisEntityHelper entityHelper;

  @Mock private MybatisStringHelper stringHelper;

  @Mock private MybatisClauseBuilder clauseBuilder;

  private MybatisCommand mybatisCommand;

  private static Set<String> originalExcludeFields;
  private MybatisProperties mybatisProperties;

  @BeforeEach
  void setUp() {
    // 생성자에서 의존성을 주입하도록 수정
    mybatisCommand = new MybatisCommand(entityHelper, stringHelper, clauseBuilder);
    mybatisProperties = new MybatisProperties();
    Set<String> excludeFields = MybatisProperties.getExcludeFields();
    excludeFields.add("__$hits$__");
    excludeFields.add("$jacocoData");

    originalExcludeFields = new HashSet<>(excludeFields);
  }

  @AfterEach
  void tearDown() {
    mybatisProperties.setExcludeFields(originalExcludeFields);
    MybatisCommand.FIELD_CACHE.clear();
  }

  @Test
  @DisplayName("기본 생성자: 의존성 객체들이 정상적으로 생성되어야 한다")
  void constructor_ShouldCreateDependenciesCorrectly() throws Exception {
    // when
    MybatisCommand mybatisCommand = new MybatisCommand();

    // then
    assertThat(getFieldValue(mybatisCommand, "stringHelper"))
        .isNotNull()
        .isInstanceOf(MybatisStringHelper.class);

    assertThat(getFieldValue(mybatisCommand, "entityHelper"))
        .isNotNull()
        .isInstanceOf(MybatisEntityHelper.class);

    assertThat(getFieldValue(mybatisCommand, "clauseBuilder"))
        .isNotNull()
        .isInstanceOf(MybatisClauseBuilder.class);

    // entityHelper의 stringHelper 의존성 검증
    MybatisEntityHelper entityHelper =
        (MybatisEntityHelper) getFieldValue(mybatisCommand, "entityHelper");
    MybatisStringHelper stringHelperInEntityHelper =
        (MybatisStringHelper) getFieldValue(entityHelper, "stringHelper");
    assertThat(stringHelperInEntityHelper).isNotNull().isInstanceOf(MybatisStringHelper.class);

    // clauseBuilder의 의존성 검증
    MybatisClauseBuilder clauseBuilder =
        (MybatisClauseBuilder) getFieldValue(mybatisCommand, "clauseBuilder");
    MybatisStringHelper stringHelperInClauseBuilder =
        (MybatisStringHelper) getFieldValue(clauseBuilder, "stringHelper");
    MybatisEntityHelper entityHelperInClauseBuilder =
        (MybatisEntityHelper) getFieldValue(clauseBuilder, "entityHelper");

    assertThat(stringHelperInClauseBuilder).isNotNull().isInstanceOf(MybatisStringHelper.class);

    assertThat(entityHelperInClauseBuilder).isNotNull().isInstanceOf(MybatisEntityHelper.class);
  }

  private Object getFieldValue(Object object, String fieldName) throws Exception {
    Field field = object.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(object);
  }

  @Test
  @DisplayName("countByMap: 정상적인 WHERE 조건으로 COUNT 쿼리가 생성되어야 한다")
  void countByMap_WithValidWhereConditions_ShouldGenerateCountQuery() {
    // given
    Map<String, Object> whereConditions = new HashMap<>();
    whereConditions.put("status", "ACTIVE");
    when(entityHelper.getTableName()).thenReturn("test_entity");

    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.WHERE("status = #{status}");
              return null;
            })
        .when(clauseBuilder)
        .buildWhereClause(any(SQL.class), eq(whereConditions));

    // when
    String sql = mybatisCommand.countByMap(whereConditions);

    // then
    assertThat(sql)
        .contains("SELECT COUNT(1) AS CNT")
        .contains("FROM test_entity")
        .contains("WHERE");
  }

  @Test
  @DisplayName("insertBatch: 빈 리스트로 호출 시 예외가 발생해야 한다")
  void insertBatch_WithEmptyList_ShouldThrowException() {
    // given
    List<Object> emptyList = Collections.emptyList();

    // when & then
    assertThatThrownBy(() -> mybatisCommand.insertBatch(emptyList))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessage("entities empty");
  }

  @Test
  @DisplayName("insertBatch: 정상적인 엔티티 리스트로 배치 INSERT 쿼리가 생성되어야 한다")
  void insertBatch_WithValidEntities_ShouldGenerateInsertBatchQuery() {
    // given
    List<TestEntity> entities =
        Arrays.asList(new TestEntity("test1", 100), new TestEntity("test2", 200));

    when(entityHelper.getTableName(TestEntity.class)).thenReturn("test_entity");
    when(entityHelper.getEntityFields(any()))
        .thenReturn(new HashSet<>(Arrays.asList("name", "value")));
    when(entityHelper.getColumnName("name")).thenReturn("name");
    when(entityHelper.getColumnName("value")).thenReturn("value");
    when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
    when(clauseBuilder.formatValueForSQL(any())).thenAnswer(i -> "'" + i.getArgument(0) + "'");

    // when
    String sql = mybatisCommand.insertBatch(entities);

    // then
    assertThat(sql)
        .contains("INSERT INTO")
        .contains("test_entity")
        .contains("name")
        .contains("value")
        .contains("test1")
        .contains("test2");
  }

  @Test
  @DisplayName("insert: 엔티티를 정상적으로 INSERT 쿼리로 변환해야 한다")
  void insert_WithValidEntity_ShouldGenerateInsertQuery() {
    // given
    TestEntity entity = new TestEntity("test", 123);

    when(entityHelper.getTableName(TestEntity.class)).thenReturn("test_entity");
    when(entityHelper.getColumnName(anyString())).thenAnswer(i -> i.getArgument(0));
    when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
    when(clauseBuilder.formatValueForSQL(eq("test"))).thenAnswer(i -> "`test`");
    when(clauseBuilder.formatValueForSQL(eq(123))).thenAnswer(i -> "123");

    // when
    String sql = mybatisCommand.insert(entity);

    // then
    assertThat(sql).contains("INSERT INTO test_entity");
  }

  @Test
  @DisplayName("getItemByMap: WHERE 조건으로 단일 항목 조회 쿼리가 생성되어야 한다")
  void getItemByMap_WithWhereConditions_ShouldGenerateSelectQuery() {
    // given
    when(entityHelper.getTableName()).thenReturn("test_entity");

    Map<String, Object> whereConditions = new HashMap<>();
    whereConditions.put("id", 1);

    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.SELECT("`user_name`");
              return null;
            })
        .when(clauseBuilder)
        .appendSelectColumns(any(SQL.class), any(), any());
    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.WHERE("id = 1");
              return null;
            })
        .when(clauseBuilder)
        .buildWhereClause(any(SQL.class), eq(whereConditions));

    // when
    String sql = mybatisCommand.getItemByMap(whereConditions);

    // then
    assertThat(sql).contains("SELECT").contains("FROM").contains("WHERE");
  }

  @Test
  @DisplayName(
      "getDistinctAndTargetItemsByMapOrderByLimitOffset: limit와 offset이 모두 null이면 페이징 처리를 하지 않아야 한다")
  void
      getDistinctAndTargetItemsByMapOrderByLimitOffset_WithNullLimitAndOffset_ShouldNotAddPaging() {
    // given
    Set<String> distinctColumns = new HashSet<>(Collections.singletonList("id"));
    Set<String> targetColumns = new HashSet<>(Arrays.asList("name", "value"));
    Map<String, Object> whereConditions = new HashMap<>();
    whereConditions.put("status", "ACTIVE");
    List<String> orderByConditions = Collections.singletonList("id DESC");

    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.SELECT("`user_name`");
              return null;
            })
        .when(clauseBuilder)
        .appendSelectColumns(any(SQL.class), any(), any());
    when(entityHelper.getTableName()).thenReturn("test_entity");

    // when
    String sql =
        mybatisCommand.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns, targetColumns, whereConditions, orderByConditions, null, null);

    // then
    assertThat(sql)
        .contains("SELECT")
        .contains("FROM test_entity")
        .doesNotContain("LIMIT")
        .doesNotContain("OFFSET");
  }

  @Test
  @DisplayName(
      "getDistinctAndTargetItemsByMapOrderByLimitOffset: limit와 offset이 주어지면 페이징 처리가 포함되어야 한다")
  void getDistinctAndTargetItemsByMapOrderByLimitOffset_WithLimitAndOffset_ShouldAddPaging() {
    // given
    Set<String> distinctColumns = new HashSet<>(Collections.singletonList("id"));
    Set<String> targetColumns = new HashSet<>(Arrays.asList("name", "value"));
    Map<String, Object> whereConditions = new HashMap<>();
    List<String> orderByConditions = Collections.singletonList("id DESC");
    Integer limit = 10;
    Integer offset = 20;

    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.SELECT("`user_name`");
              return null;
            })
        .when(clauseBuilder)
        .appendSelectColumns(any(SQL.class), any(), any());
    when(entityHelper.getTableName()).thenReturn("test_entity");

    // when
    String sql =
        mybatisCommand.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns, targetColumns, whereConditions, orderByConditions, limit, offset);

    // then
    assertThat(sql).contains("LIMIT 10").contains("OFFSET 20");
  }

  @Test
  @DisplayName("updateMapByMap: 정상적인 업데이트 맵과 조건으로 UPDATE 쿼리가 생성되어야 한다")
  void updateMapByMap_WithValidUpdateAndWhereConditions_ShouldGenerateUpdateQuery() {
    // given
    Map<String, Object> updateMap = new HashMap<>();
    updateMap.put("name", "updatedName");
    updateMap.put("value", 999);

    Map<String, Object> whereConditions = new HashMap<>();
    whereConditions.put("id", 1);

    when(entityHelper.getTableName()).thenReturn("test_entity");
    when(entityHelper.getColumnName(anyString())).thenAnswer(i -> i.getArgument(0));
    when(clauseBuilder.buildEqualClause(anyString(), any()))
        .thenAnswer(i -> i.getArgument(0) + " = '" + i.getArgument(1) + "'");
    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.WHERE("id = 1");
              return null;
            })
        .when(clauseBuilder)
        .buildWhereClause(any(SQL.class), eq(whereConditions));

    // when
    String sql = mybatisCommand.updateMapByMap(updateMap, whereConditions);

    // then
    assertThat(sql).contains("UPDATE").contains("test_entity").contains("SET").contains("WHERE");
  }

  @Test
  @DisplayName("deleteByMap: 정상적인 WHERE 조건으로 DELETE 쿼리가 생성되어야 한다")
  void deleteByMap_WithValidWhereConditions_ShouldGenerateDeleteQuery() {
    // given
    Map<String, Object> whereConditions = new HashMap<>();
    whereConditions.put("status", "INACTIVE");
    whereConditions.put("lastLoginDate", "2024-01-01");

    when(entityHelper.getTableName()).thenReturn("test_table");

    doAnswer(
            invocation -> {
              SQL sql = invocation.getArgument(0);
              sql.WHERE("status = #{status}");
              sql.WHERE("last_login_date = #{lastLoginDate}");
              return null;
            })
        .when(clauseBuilder)
        .buildWhereClause(any(SQL.class), eq(whereConditions));

    // when
    String sql = mybatisCommand.deleteByMap(whereConditions);

    // then
    verify(clauseBuilder).validateWhereConditions(whereConditions);
    verify(clauseBuilder).buildWhereClause(any(SQL.class), eq(whereConditions));
    verify(clauseBuilder).ensureWhereClause(any(SQL.class));

    assertThat(sql)
        .contains("DELETE FROM test_table")
        .contains("WHERE")
        .contains("status = #{status}")
        .contains("last_login_date = #{lastLoginDate}");
  }

  @Test
  @DisplayName("deleteByMap: WHERE 절이 비어있으면 예외가 발생해야 한다")
  void deleteByMap_WithEmptyWhereClause_ShouldThrowException() {
    // given
    Map<String, Object> whereConditions = new HashMap<>();
    when(entityHelper.getTableName()).thenReturn("test_table");

    doThrow(new MybatisRepositoryException("WHERE clause is empty"))
        .when(clauseBuilder)
        .buildWhereClause(any(SQL.class), eq(whereConditions));

    // when & then
    assertThatThrownBy(() -> mybatisCommand.deleteByMap(whereConditions))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessage("WHERE clause is empty");

    verify(clauseBuilder).validateWhereConditions(whereConditions);
    verify(clauseBuilder).buildWhereClause(any(SQL.class), eq(whereConditions));
  }

  @Test
  @DisplayName("toMap: 필드 접근 시 예외가 발생하면 로그를 남기고 계속 진행해야 한다")
  void toMap_WhenFieldAccessThrowsException_ShouldLogAndContinue() {
    // given
    class TestEntityWithException {
      private final String name;
      private final Integer value;

      public TestEntityWithException() {
        this.name = "test";
        this.value = 100;
      }

      public String getName() {
        throw new RuntimeException("Test exception");
      }

      public Integer getValue() {
        return value;
      }
    }

    TestEntityWithException entity = new TestEntityWithException();

    // when
    Map<String, Object> result = MybatisCommand.toMap(entity);

    // then
    assertThat(result)
        .containsKey("value")
        .hasEntrySatisfying("value", value -> assertThat(value).isEqualTo(100));
  }

  private static class TestEntity {
    private String name;
    private Integer value;

    public TestEntity(String name, Integer value) {
      this.name = name;
      this.value = value;
    }

    // Getters and setters
    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Integer getValue() {
      return value;
    }

    public void setValue(Integer value) {
      this.value = value;
    }
  }
}
