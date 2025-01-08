package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import java.time.*;
import java.util.*;
import org.apache.ibatis.jdbc.SQL;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisClauseBuilderTest {

  enum NormalEnum {
    TEST_VALUE
  }

  @Mock private MybatisStringHelper stringHelper;

  @Mock private MybatisEntityHelper entityHelper;

  private MybatisClauseBuilder clauseBuilder;

  @BeforeEach
  void setUp() {
    clauseBuilder = new MybatisClauseBuilder(stringHelper, entityHelper);
  }

  @Nested
  @DisplayName("appendSelectColumns 메소드는")
  class AppendSelectColumnsTest {
    private SQL sql;
    private Set<String> distinctColumns;
    private Set<String> targetColumns;

    @BeforeEach
    void setUp() {
      // given
      sql = new SQL();
      distinctColumns = new HashSet<>();
      targetColumns = new HashSet<>();
    }

    @Test
    @DisplayName("모든 컬럼이 비어있으면 전체 필드를 SELECT한다")
    void selectAllFieldsWhenColumnsAreEmpty() {
      // given
      when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
      Set<String> fields = new HashSet<>(Arrays.asList("id", "userName", "email"));
      when(entityHelper.getEntityFields()).thenReturn(fields);
      when(entityHelper.getColumnName("id")).thenReturn("user_id");
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");
      when(entityHelper.getColumnName("email")).thenReturn("user_email");

      // when
      clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns);

      String sqlString = sql.toString();

      // then
      assertThat(sqlString).contains("SELECT `user_id`");
      assertThat(sqlString).contains("`user_name`");
      assertThat(sqlString).contains("`user_email`");
    }

    @Test
    @DisplayName("DISTINCT 컬럼이 지정되면 해당 컬럼을 DISTINCT로 SELECT한다")
    void selectDistinctColumnsWhenSpecified() {
      // given
      when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");
      distinctColumns.add("userName");

      // when
      clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns);

      // then
      assertThat(sql.toString()).contains("SELECT DISTINCT `user_name`");
    }

    @Test
    @DisplayName("일반 컬럼과 DISTINCT 컬럼을 함께 처리한다")
    void handleBothDistinctAndNormalColumns() {
      // given
      when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");
      when(entityHelper.getColumnName("email")).thenReturn("user_email");

      distinctColumns.add("userName");
      targetColumns.add("email");
      targetColumns.add("userName"); // 중복 컬럼은 DISTINCT로만 처리됨

      // when
      clauseBuilder.appendSelectColumns(sql, distinctColumns, targetColumns);

      String sqlString = sql.toString();

      // then
      assertThat(sqlString).contains("SELECT DISTINCT `user_name`");
      assertThat(sqlString).contains("`user_email`");
      assertThat(sqlString.indexOf("`user_name`"))
          .isEqualTo(sqlString.lastIndexOf("`user_name`")); // 중복 체크
    }
  }

  @Nested
  @DisplayName("ensureWhereClause 메소드는")
  class EnsureWhereClauseTest {
    private SQL sql;

    @BeforeEach
    void setUp() {
      sql = new SQL();
    }

    @Test
    @DisplayName("WHERE 절이 없으면 예외를 발생시킨다")
    void throwExceptionWhenWhereClauseIsMissing() {
      sql.SELECT("*").FROM("test_table");

      assertThatThrownBy(
              new ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                  clauseBuilder.ensureWhereClause(sql);
                }
              })
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessage("whereConditions are required");
    }

    @Test
    @DisplayName("WHERE 절이 있으면 예외를 발생시키지 않는다")
    void doNotThrowExceptionWhenWhereClauseExists() {
      sql.SELECT("*").FROM("test_table").WHERE("id = 1");

      assertThatCode(
              new ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                  clauseBuilder.ensureWhereClause(sql);
                }
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("buildInClause 메소드는")
  class BuildInClauseTest {

    @Test
    @DisplayName("Set이 아닌 값이 전달되면 예외를 발생시킨다")
    void throwExceptionWhenValueIsNotSet() {
      assertThatThrownBy(
              new ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                  clauseBuilder.buildInClause("column", "not-a-set", false);
                }
              })
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessageContaining("conditionType 'in' requires Set");
    }

    @Test
    @DisplayName("빈 Set이 전달되면 예외를 발생시킨다")
    void throwExceptionWhenSetIsEmpty() {
      assertThatThrownBy(
              new ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                  clauseBuilder.buildInClause("column", new HashSet<String>(), false);
                }
              })
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessageContaining("WHERE - empty in clause : column");
    }

    @Test
    @DisplayName("IN 절을 올바르게 생성한다")
    void buildProperInClause() {
      when(stringHelper.escapeSingleQuote("value1")).thenReturn("value1");
      when(stringHelper.escapeSingleQuote("value2")).thenReturn("value2");

      Set<String> values = new LinkedHashSet<String>();
      values.add("value1");
      values.add("value2");

      String result = clauseBuilder.buildInClause("test_column", values, false);

      assertThat(result).isEqualTo("`test_column`  IN ('value1', 'value2')");
    }

    @Test
    @DisplayName("NOT IN 절을 올바르게 생성한다")
    void buildProperNotInClause() {
      when(stringHelper.escapeSingleQuote("value1")).thenReturn("value1");
      when(stringHelper.escapeSingleQuote("value2")).thenReturn("value2");

      Set<String> values = new LinkedHashSet<String>();
      values.add("value1");
      values.add("value2");

      String result = clauseBuilder.buildInClause("test_column", values, true);

      assertThat(result).isEqualTo("`test_column` NOT IN ('value1', 'value2')");
    }

    @Test
    @DisplayName("Map 타입을 SQL 문자열로 변환한다")
    void formatMapValue() {
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      map.put("key1", "value1");
      map.put("key2", 100);

      when(stringHelper.escapeSingleQuote("key1")).thenReturn("key1");
      when(stringHelper.escapeSingleQuote("key2")).thenReturn("key2");
      when(stringHelper.escapeSingleQuote("value1")).thenReturn("value1");
      when(stringHelper.escapeSingleQuote("100")).thenReturn("100");

      String result = clauseBuilder.formatValueForSQL(map);
      assertThat(result).isEqualTo("\"{\"key1\":\"value1\", \"key2\":100}\"");
    }

    @Test
    @DisplayName("기본 타입을 SQL 문자열로 변환한다")
    void formatPrimitiveValue() {
      when(stringHelper.escapeSingleQuote("100")).thenReturn("100");
      when(stringHelper.escapeSingleQuote("true")).thenReturn("true");

      assertThat(clauseBuilder.formatValueForSQL(100)).isEqualTo("100");
      assertThat(clauseBuilder.formatValueForSQL(true)).isEqualTo("true");
    }

    @Test
    @DisplayName("null 값이 전달되면 예외 메시지에 null이 포함된다")
    void throwExceptionWhenValueIsNull() {
      // given
      String columnName = "test_column";
      Object nullValue = null;

      // when & then
      assertThatThrownBy(() -> clauseBuilder.buildInClause(columnName, nullValue, false))
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessageContaining("conditionType 'in' requires Set, yours: null");
    }

    @Test
    @DisplayName("Set이 아닌 타입이 전달되면 예외 메시지에 해당 타입이 포함된다")
    void throwExceptionWithActualType() {
      // given
      String columnName = "test_column";
      String invalidValue = "invalid";

      // when & then
      assertThatThrownBy(() -> clauseBuilder.buildInClause(columnName, invalidValue, false))
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessageContaining("conditionType 'in' requires Set, yours: class java.lang.String");
    }

    @Test
    @DisplayName("NOT IN절에서 Set이 아닌 타입이 전달되면 예외 메시지에 notIn이 포함된다")
    void throwExceptionWithNotInMessage() {
      // given
      String columnName = "test_column";
      String invalidValue = "invalid";

      // when & then
      assertThatThrownBy(() -> clauseBuilder.buildInClause(columnName, invalidValue, true))
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessageContaining("conditionType 'notIn' requires Set");
    }
  }

  @Nested
  @DisplayName("buildEqualClause 메소드는")
  class BuildEqualClauseTest {

    @Test
    @DisplayName("문자열 값에 대한 equals 절을 생성한다")
    void buildEqualClauseWithStringValue() {
      when(stringHelper.escapeSingleQuote("test_value")).thenReturn("test_value");

      String result = clauseBuilder.buildEqualClause("column_name", "test_value");

      assertThat(result).isEqualTo("`column_name` = 'test_value'");
    }

    @Test
    @DisplayName("null 값에 대한 equals 절을 생성한다")
    void buildEqualClauseWithNullValue() {
      String result = clauseBuilder.buildEqualClause("column_name", null);

      assertThat(result).isEqualTo("`column_name` = null");
    }
  }

  @Nested
  @DisplayName("validateWhereConditions 메소드는")
  class ValidateWhereConditionsTest {

    @Test
    @DisplayName("where 조건이 null이면 예외를 발생시킨다")
    void throwExceptionWhenWhereConditionsIsNull() {
      assertThatThrownBy(() -> clauseBuilder.validateWhereConditions(null))
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessage("'where' Conditions is required");
    }

    @Test
    @DisplayName("where 조건이 비어있으면 예외를 발생시킨다")
    void throwExceptionWhenWhereConditionsIsEmpty() {
      assertThatThrownBy(() -> clauseBuilder.validateWhereConditions(new HashMap<>()))
          .isInstanceOf(MybatisRepositoryException.class)
          .hasMessage("'where' Conditions is required");
    }
  }

  @Nested
  @DisplayName("buildWhereClause 메소드는")
  class BuildWhereClauseTest {

    private SQL sql;

    @BeforeEach
    void setUp() {
      // given
      sql = new SQL();
      sql.SELECT("*").FROM("test_table");
    }

    @Test
    @DisplayName("조건이 null이면 where절을 생성하지 않는다")
    void doNotBuildWhereClauseWhenConditionsIsNull() {
      // when
      clauseBuilder.buildWhereClause(sql, null);

      // then
      assertThat(sql.toString()).doesNotContain("WHERE");
    }

    @Test
    @DisplayName("eq 조건으로 where절을 생성한다")
    void buildWhereClauseWithEqCondition() {
      // given
      when(stringHelper.substringBefore("userName:eq")).thenReturn("userName");
      when(stringHelper.substringAfter("userName:eq")).thenReturn("eq");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");

      Map<String, Object> conditions = new HashMap<>();
      conditions.put("userName:eq", "test");

      // when
      clauseBuilder.buildWhereClause(sql, conditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`user_name` = 'test')");
    }

    @Test
    @DisplayName("EQ 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenEqCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("name:eq", "test");

      when(stringHelper.substringBefore("name:eq")).thenReturn("name");
      when(stringHelper.substringAfter("name:eq")).thenReturn("eq");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("name")).thenReturn("name");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`name` = 'test')");
    }

    @Test
    @DisplayName("IN 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenInCondition_thenBuildWhereClauseCorrectly() {
      // given
      Set<String> values = new HashSet<>();
      values.add("value1");
      values.add("value2");

      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("type:in", values);

      when(stringHelper.substringBefore(anyString())).thenReturn("type");
      when(stringHelper.substringAfter(anyString())).thenReturn("in");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("type")).thenReturn("type");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString())
          .contains("WHERE (`type`  IN (")
          .contains("'value1'")
          .contains("'value2'");
    }

    @Test
    @DisplayName("NULL 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenNullCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("deletedAt:null", null);

      when(stringHelper.substringBefore(anyString())).thenReturn("deletedAt");
      when(stringHelper.substringAfter(anyString())).thenReturn("null");
      when(entityHelper.getColumnName("deletedAt")).thenReturn("deleted_at");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`deleted_at` IS NULL)");
    }

    @Test
    @DisplayName("CONTAINS 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenContainsCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("description:contains", "test");

      when(stringHelper.substringBefore(anyString())).thenReturn("description");
      when(stringHelper.substringAfter(anyString())).thenReturn("contains");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("description")).thenReturn("description");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (INSTR(`description`, 'test') > 0)");
    }

    @Test
    @DisplayName("GT 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenGtCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("createdAt:gt", Instant.parse("2024-01-01T00:00:00Z"));

      when(stringHelper.substringBefore(anyString())).thenReturn("createdAt");
      when(stringHelper.substringAfter(anyString())).thenReturn("gt");
      when(entityHelper.getColumnName("createdAt")).thenReturn("created_at");
      when(stringHelper.instantToString(any(Instant.class), anyString()))
          .thenReturn("2024-01-01 00:00:00.000");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`created_at` > '2024-01-01 00:00:00.000')");
    }

    @Test
    @DisplayName("NE 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenNeCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("status:ne", "INACTIVE");

      when(stringHelper.substringBefore(anyString())).thenReturn("status");
      when(stringHelper.substringAfter(anyString())).thenReturn("ne");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("status")).thenReturn("status");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`status` <> 'INACTIVE')");
    }

    @Test
    @DisplayName("NOT 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenNotCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("status:not", "DELETED");

      when(stringHelper.substringBefore(anyString())).thenReturn("status");
      when(stringHelper.substringAfter(anyString())).thenReturn("not");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("status")).thenReturn("status");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`status` <> 'DELETED')");
    }

    @Test
    @DisplayName("NOT_IN 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenNotInCondition_thenBuildWhereClauseCorrectly() {
      // given
      Set<String> values = new HashSet<>();
      values.add("DRAFT");
      values.add("PENDING");

      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("status:notIn", values);

      when(stringHelper.substringBefore(anyString())).thenReturn("status");
      when(stringHelper.substringAfter(anyString())).thenReturn("notIn");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("status")).thenReturn("status");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`status` NOT IN ('DRAFT', 'PENDING'))");
    }

    @Test
    @DisplayName("IS_NOT_NULL 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenIsNotNullCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("deletedAt:notNull", null);

      when(stringHelper.substringBefore(anyString())).thenReturn("deletedAt");
      when(stringHelper.substringAfter(anyString())).thenReturn("notNull");
      when(entityHelper.getColumnName("deletedAt")).thenReturn("deleted_at");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`deleted_at` IS NOT NULL)");
    }

    @Test
    @DisplayName("NOT_CONTAINS 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenNotContainsCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("description:notContains", "spam");

      when(stringHelper.substringBefore(anyString())).thenReturn("description");
      when(stringHelper.substringAfter(anyString())).thenReturn("notContains");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("description")).thenReturn("description");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (INSTR(`description`, 'spam') = 0)");
    }

    @Test
    @DisplayName("STARTS_WITH 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenStartsWithCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("email:startsWith", "admin");

      when(stringHelper.substringBefore(anyString())).thenReturn("email");
      when(stringHelper.substringAfter(anyString())).thenReturn("startsWith");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("email")).thenReturn("email");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (INSTR(`email`, 'admin') = 1)");
    }

    @Test
    @DisplayName("ENDS_WITH 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenEndsWithCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("email:endsWith", "@gmail.com");

      when(stringHelper.substringBefore(anyString())).thenReturn("email");
      when(stringHelper.substringAfter(anyString())).thenReturn("endsWith");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("email")).thenReturn("email");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString())
          .contains("WHERE (RIGHT(`email`, CHAR_LENGTH('@gmail.com')) = '@gmail.com')");
    }

    @Test
    @DisplayName("LT 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenLtCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("price:lt", 1000);

      when(stringHelper.substringBefore(anyString())).thenReturn("price");
      when(stringHelper.substringAfter(anyString())).thenReturn("lt");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("price")).thenReturn("price");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`price` < 1000)");
    }

    @Test
    @DisplayName("LTE 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenLteCondition_thenBuildWhereClauseCorrectly() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("price:lte", 1000);

      when(stringHelper.substringBefore(anyString())).thenReturn("price");
      when(stringHelper.substringAfter(anyString())).thenReturn("lte");
      when(stringHelper.escapeSingleQuote(anyString())).thenAnswer(i -> i.getArgument(0));
      when(entityHelper.getColumnName("price")).thenReturn("price");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`price` <= 1000)");
    }

    @Test
    @DisplayName("GTE 조건으로 WHERE 절이 정상 생성되어야 한다")
    void whenGteCondition_thenBuildWhereClauseCorrectly() {
      // given
      Instant now = Instant.parse("2024-01-01T00:00:00Z");
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("createdAt:gte", now);

      when(stringHelper.substringBefore(anyString())).thenReturn("createdAt");
      when(stringHelper.substringAfter(anyString())).thenReturn("gte");
      when(entityHelper.getColumnName("createdAt")).thenReturn("created_at");
      when(stringHelper.instantToString(any(Instant.class), anyString()))
          .thenReturn("2024-01-01 00:00:00.000");

      // when
      clauseBuilder.buildWhereClause(sql, whereConditions);

      // then
      assertThat(sql.toString()).contains("WHERE (`created_at` >= '2024-01-01 00:00:00.000')");
    }
  }

  @Nested
  @DisplayName("formatValueForSQL 메소드는")
  class FormatValueForSQLTest {

    @Test
    @DisplayName("ISO8601 형식의 문자열이 주어지면 Instant로 변환하여 포맷팅한다")
    void formatValueForSQL_WhenISO8601StringGiven_ThenConvertToInstantAndFormat() {
      // given
      String iso8601String = "2024-01-07T12:00:00Z";
      Instant instant = Instant.parse(iso8601String);

      when(stringHelper.isISO8601String(iso8601String)).thenReturn(true);
      when(stringHelper.instantToString(eq(instant), any())).thenReturn("2024-01-07 12:00:00.000");

      // when
      String result = clauseBuilder.formatValueForSQL(iso8601String);

      // then
      assertThat(result).isEqualTo("'2024-01-07 12:00:00.000'");
    }

    @Test
    @DisplayName("일반 문자열이 주어지면 ISO8601 변환 없이 포맷팅한다")
    void formatValueForSQL_WhenNormalStringGiven_ThenFormatWithoutConversion() {
      // given
      String normalString = "hello world";

      when(stringHelper.isISO8601String(normalString)).thenReturn(false);
      when(stringHelper.escapeSingleQuote(normalString)).thenReturn(normalString);

      // when
      String result = clauseBuilder.formatValueForSQL(normalString);

      // then
      assertThat(result).isEqualTo("'hello world'");
    }

    @Test
    @DisplayName("Instant 타입을 SQL 문자열로 변환한다")
    void formatInstantValue() {
      // given
      Instant instant = LocalDateTime.of(2024, 1, 7, 10, 30, 0).toInstant(ZoneOffset.UTC);
      when(stringHelper.instantToString(instant, "yyyy-MM-dd HH:mm:ss.SSS"))
          .thenReturn("2024-01-07 10:30:00.000");

      // when
      String result = clauseBuilder.formatValueForSQL(instant);

      // then
      assertThat(result).isEqualTo("'2024-01-07 10:30:00.000'");
    }

    @Test
    @DisplayName("Date 타입을 SQL 문자열로 변환한다")
    void formatDateValue() {
      Date date = new Date(124, 0, 7, 10, 30, 0); // 2024-01-07 10:30:00
      String result = clauseBuilder.formatValueForSQL(date);
      assertThat(result).matches("'2024-01-07 10:30:00\\.\\d{3}'");
    }

    @Test
    @DisplayName("LocalDateTime 타입을 SQL 문자열로 변환한다")
    void formatLocalDateTimeValue() {
      LocalDateTime dateTime = LocalDateTime.of(2024, 1, 7, 10, 30, 0);
      String result = clauseBuilder.formatValueForSQL(dateTime);
      assertThat(result).isEqualTo("'2024-01-07 10:30:00.000'");
    }

    @Test
    @DisplayName("LocalDate 타입을 SQL 문자열로 변환한다")
    void formatLocalDateValue() {
      LocalDate date = LocalDate.of(2024, 1, 7);
      String result = clauseBuilder.formatValueForSQL(date);
      assertThat(result).isEqualTo("'2024-01-07'");
    }

    @Test
    @DisplayName("OffsetDateTime 타입을 SQL 문자열로 변환한다")
    void formatOffsetDateTimeValue() {
      OffsetDateTime dateTime = OffsetDateTime.of(2024, 1, 7, 10, 30, 0, 0, ZoneOffset.UTC);
      when(stringHelper.instantToString(dateTime.toInstant(), "yyyy-MM-dd HH:mm:ss.SSS"))
          .thenReturn("2024-01-07 10:30:00.000");

      String result = clauseBuilder.formatValueForSQL(dateTime);
      assertThat(result).isEqualTo("'2024-01-07 10:30:00.000'");
    }

    @Test
    @DisplayName("Enum 타입을 SQL 문자열로 변환한다")
    void formatEnumValue() {
      TestEnum testEnum = TestEnum.TEST;
      String result = clauseBuilder.formatValueForSQL(testEnum);
      assertThat(result).isEqualTo("'test_value'");
    }

    @Test
    @DisplayName("일반 Enum이 주어지면 name()을 사용하여 포맷팅한다")
    void formatValueForSQL_WhenNormalEnumGiven_ThenFormatUsingName() {
      // given
      NormalEnum testEnum = NormalEnum.TEST_VALUE;

      // when
      String result = clauseBuilder.formatValueForSQL(testEnum);

      // then
      assertThat(result).isEqualTo("'TEST_VALUE'");
    }

    @Test
    @DisplayName("Collection 타입을 SQL 문자열로 변환한다")
    void formatCollectionValue() {
      // given
      List<String> values = Arrays.asList("value1", "value2");
      when(stringHelper.escapeSingleQuote("value1")).thenReturn("value1");
      when(stringHelper.escapeSingleQuote("value2")).thenReturn("value2");

      // when
      String result = clauseBuilder.formatValueForSQL(values);

      // then
      assertThat(result).isEqualTo("'[\"value1\", \"value2\"]'");
    }

    @Test
    @DisplayName("빈 Collection을 SQL 문자열로 변환한다")
    void formatEmptyCollectionValue() {
      // given
      List<String> values = Collections.emptyList();

      // when
      String result = clauseBuilder.formatValueForSQL(values);

      // then
      assertThat(result).isEqualTo("'[]'");
    }

    @Test
    @DisplayName("null 값을 포함한 Collection을 SQL 문자열로 변환한다")
    void formatCollectionWithNullValue() {
      // given
      List<String> values = Arrays.asList("value1", null, "value2");
      when(stringHelper.escapeSingleQuote("value1")).thenReturn("value1");
      when(stringHelper.escapeSingleQuote("value2")).thenReturn("value2");

      // when
      String result = clauseBuilder.formatValueForSQL(values);

      // then
      assertThat(result).isEqualTo("'[\"value1\", null, \"value2\"]'");
    }

    @Test
    @DisplayName("문자열 값에 대한 equals 절을 생성한다")
    void buildEqualClauseWithStringValue() {
      when(stringHelper.escapeSingleQuote("test_value")).thenReturn("test_value");

      String result = clauseBuilder.buildEqualClause("column_name", "test_value");

      assertThat(result).isEqualTo("`column_name` = 'test_value'");
    }

    @Test
    @DisplayName("null 값에 대한 equals 절을 생성한다")
    void buildEqualClauseWithNullValue() {
      String result = clauseBuilder.buildEqualClause("column_name", null);

      assertThat(result).isEqualTo("`column_name` = null");
    }
  }

  @Nested
  @DisplayName("appendOrderBy 메소드는")
  class AppendOrderByTest {

    private SQL sql;

    @BeforeEach
    void setUp() {
      sql = new SQL();
      sql.SELECT("*").FROM("users");
    }

    @Test
    @DisplayName("정렬 조건이 null이면 order by절을 생성하지 않는다")
    void doNotAppendOrderByWhenConditionsIsNull() {
      // when
      clauseBuilder.appendOrderBy(sql, null);
      // then
      assertThat(sql.toString()).doesNotContain("ORDER BY");
    }

    @Test
    @DisplayName("오름차순 정렬 조건을 처리한다")
    void appendAscendingOrderBy() {
      // given
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");
      when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");
      List<String> orderByConditions = Collections.singletonList("userName");

      // when
      clauseBuilder.appendOrderBy(sql, orderByConditions);

      // then
      assertThat(sql.toString()).contains("ORDER BY `user_name` ASC");
    }

    @Test
    @DisplayName("내림차순 정렬 조건을 처리한다")
    void appendDescendingOrderBy() {
      // given
      when(entityHelper.getColumnName("userName")).thenReturn("user_name");
      when(stringHelper.wrapIdentifier(anyString())).thenAnswer(i -> "`" + i.getArgument(0) + "`");

      List<String> orderByConditions = Collections.singletonList("-userName");

      // when
      clauseBuilder.appendOrderBy(sql, orderByConditions);

      // then
      assertThat(sql.toString()).contains("ORDER BY `user_name` DESC");
    }
  }

  public enum TestEnum implements ValueEnum {
    TEST("test_value");

    private final String value;

    TestEnum(String value) {
      this.value = value;
    }

    @Override
    public String getValue() {
      return value;
    }
  }
}
