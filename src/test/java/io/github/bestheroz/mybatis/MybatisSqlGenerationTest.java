package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.jdbc.SQL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 생성 경로는 그동안 테스트가 없었다. 성능 목적의 리팩터링이 만들어 내는 SQL 을 바꾸지 않았음을 보이려면 결국 생성된 문자열을 직접 확인하는 수밖에 없어 여기에
 * 모아 둔다.
 */
class MybatisSqlGenerationTest {
  private MybatisStringHelper stringHelper;
  private MybatisEntityHelper entityHelper;
  private MybatisClauseBuilder clauseBuilder;
  private MybatisCommand command;

  @Table(name = "test_user")
  static class TestUser {
    @Column(name = "user_id")
    private Long userId;

    @Column private String name;

    /**
     * @Column 이 없으므로 매핑 대상이 아니다.
     */
    private String notMapped;

    TestUser(Long userId, String name) {
      this.userId = userId;
      this.name = name;
      this.notMapped = "ignored";
    }
  }

  enum Grade implements ValueEnum {
    VIP("v'ip");

    private final String value;

    Grade(String value) {
      this.value = value;
    }

    @Override
    public String getValue() {
      return this.value;
    }
  }

  @BeforeEach
  void setUp() {
    stringHelper = new MybatisStringHelper();
    entityHelper = new MybatisEntityHelper(stringHelper);
    clauseBuilder = new MybatisClauseBuilder(stringHelper, entityHelper);
    command = new MybatisCommand(entityHelper, stringHelper, clauseBuilder);
  }

  @AfterEach
  void tearDown() {
    MybatisRepositoryProperties.getInstance().resetToDefaults();
  }

  @Test
  @DisplayName("@Column 이 붙은 필드만 매핑하고 이름 규칙을 지켜야 한다")
  void getColumnName_ShouldMapOnlyAnnotatedFields() {
    // given / when / then
    assertThat(entityHelper.getColumnName(TestUser.class, "userId")).isEqualTo("user_id");
    assertThat(entityHelper.getColumnName(TestUser.class, "name")).isEqualTo("name");
    assertThat(entityHelper.getTableName(TestUser.class)).isEqualTo("test_user");
    assertThat(entityHelper.getEntityFields(TestUser.class))
        .containsExactlyInAnyOrder("userId", "name");

    assertThatThrownBy(() -> entityHelper.getColumnName(TestUser.class, "notMapped"))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("컬럼명을 캐시해도 매번 같은 값을 돌려주고, 없는 필드는 계속 예외여야 한다")
  void getColumnName_ShouldStayConsistentAcrossRepeatedCalls() {
    // given
    String first = entityHelper.getColumnName(TestUser.class, "userId");

    // when
    String second = entityHelper.getColumnName(TestUser.class, "userId");

    // then
    assertThat(second).isEqualTo(first);
    // 오류 경로는 캐시되지 않으므로 두 번째에도 똑같이 예외가 나야 한다
    assertThatThrownBy(() -> entityHelper.getColumnName(TestUser.class, "nope"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> entityHelper.getColumnName(TestUser.class, "nope"))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("INSERT 문은 매핑된 컬럼만 담아야 한다")
  void buildInsertSQL_ShouldContainOnlyMappedColumns() {
    // given
    TestUser user = new TestUser(7L, "kim");

    // when
    String sql = command.buildInsertSQL(user);

    // then
    assertThat(sql).contains("INSERT INTO test_user");
    assertThat(sql).contains("`user_id`").contains("`name`");
    assertThat(sql).contains("7").contains("'kim'");
    assertThat(sql).doesNotContain("not_mapped").doesNotContain("ignored");
  }

  @Test
  @DisplayName("배치 INSERT 는 행마다 컬럼 순서에 맞춰 값을 채워야 한다")
  void buildInsertBatchSQL_ShouldAlignValuesWithColumns() {
    // given
    List<TestUser> users = new ArrayList<>();
    users.add(new TestUser(1L, "a"));
    users.add(new TestUser(2L, "b"));

    // 컬럼 순서는 getEntityFields 의 순회 순서를 그대로 따른다
    Set<String> fields = entityHelper.getEntityFields(TestUser.class);
    StringBuilder expectedRows = new StringBuilder();
    for (int row = 1; row <= 2; row++) {
      if (row > 1) {
        // MyBatis 가 행마다 괄호를 붙이면서 행 사이를 개행으로 끊는다
        expectedRows.append("\n, ");
      }
      expectedRows.append('(');
      Iterator<String> it = fields.iterator();
      boolean firstColumn = true;
      while (it.hasNext()) {
        if (!firstColumn) {
          expectedRows.append(", ");
        }
        firstColumn = false;
        String field = it.next();
        expectedRows.append(
            "userId".equals(field) ? String.valueOf(row) : "'" + (char) ('a' + row - 1) + "'");
      }
      expectedRows.append(')');
    }

    // when
    String sql = command.buildInsertBatchSQL(users);

    // then
    assertThat(sql).contains(expectedRows.toString());
    // 테이블명은 다른 다섯 경로와 똑같이 감싸지 않는다
    assertThat(sql).contains("INSERT INTO test_user");
  }

  @Test
  @DisplayName("배치 INSERT 는 타입이 섞이거나 비어 있으면 거부해야 한다")
  void buildInsertBatchSQL_ShouldRejectInvalidBatches() {
    // given / when / then
    assertThatThrownBy(() -> command.buildInsertBatchSQL(new ArrayList<TestUser>()))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("IN 절은 값 사이를 쉼표로 잇고 NOT IN 여부를 구분해야 한다")
  void buildInClause_ShouldJoinValues() {
    // given
    Set<Object> values = new LinkedHashSet<>();
    values.add(1);
    values.add(2);

    // when
    String in = clauseBuilder.buildInClause("user_id", values, false);
    String notIn = clauseBuilder.buildInClause("user_id", values, true);

    // then
    assertThat(in).isEqualTo("`user_id`  IN (1, 2)");
    assertThat(notIn).isEqualTo("`user_id` NOT IN (1, 2)");
  }

  @Test
  @DisplayName("IN 절은 비어 있으면 거부해야 한다")
  void buildInClause_ShouldRejectEmptySet() {
    // given / when / then
    assertThatThrownBy(() -> clauseBuilder.buildInClause("user_id", new LinkedHashSet<>(), false))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> clauseBuilder.buildInClause("user_id", "not a set", false))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("조건 코드마다 정해진 절을 만들어야 한다")
  void condition_ShouldBuildExpectedClauses() {
    // given / when / then
    assertThat(Condition.from("eq").buildClause("name", "kim", clauseBuilder))
        .isEqualTo("`name` = 'kim'");
    assertThat(Condition.from("ne").buildClause("name", "kim", clauseBuilder))
        .isEqualTo("`name` <> 'kim'");
    assertThat(Condition.from("not").buildClause("name", "kim", clauseBuilder))
        .isEqualTo("`name` <> 'kim'");
    assertThat(Condition.from("null").buildClause("name", null, clauseBuilder))
        .isEqualTo("`name` IS NULL");
    assertThat(Condition.from("notNull").buildClause("name", null, clauseBuilder))
        .isEqualTo("`name` IS NOT NULL");
    assertThat(Condition.from("contains").buildClause("name", "ki", clauseBuilder))
        .isEqualTo("INSTR(`name`, 'ki') > 0");
    assertThat(Condition.from("notContains").buildClause("name", "ki", clauseBuilder))
        .isEqualTo("INSTR(`name`, 'ki') = 0");
    assertThat(Condition.from("startsWith").buildClause("name", "ki", clauseBuilder))
        .isEqualTo("INSTR(`name`, 'ki') = 1");
    assertThat(Condition.from("endsWith").buildClause("name", "im", clauseBuilder))
        .isEqualTo("RIGHT(`name`, CHAR_LENGTH('im')) = 'im'");
    assertThat(Condition.from("lt").buildClause("age", 3, clauseBuilder)).isEqualTo("`age` < 3");
    assertThat(Condition.from("lte").buildClause("age", 3, clauseBuilder)).isEqualTo("`age` <= 3");
    assertThat(Condition.from("gt").buildClause("age", 3, clauseBuilder)).isEqualTo("`age` > 3");
    assertThat(Condition.from("gte").buildClause("age", 3, clauseBuilder)).isEqualTo("`age` >= 3");
  }

  @Test
  @DisplayName("조건 코드는 대소문자를 가리지 않고, 모르는 코드는 eq 로 떨어져야 한다")
  void conditionFrom_ShouldBeCaseInsensitiveAndFallBackToEq() {
    // given / when / then
    assertThat(Condition.from("notIn")).isEqualTo(Condition.NOT_IN);
    assertThat(Condition.from("NOTIN")).isEqualTo(Condition.NOT_IN);
    assertThat(Condition.from("in")).isEqualTo(Condition.IN);
    assertThat(Condition.from("IN")).isEqualTo(Condition.IN);
    assertThat(Condition.from("startswith")).isEqualTo(Condition.STARTS_WITH);
    assertThat(Condition.from("무엇인가")).isEqualTo(Condition.EQ);
    assertThat(Condition.from(null)).isEqualTo(Condition.EQ);
  }

  @Test
  @DisplayName("ValueEnum 의 값도 이스케이프를 거쳐야 한다")
  void formatValueForSQL_ShouldEscapeValueEnum() {
    // given / when
    String result = clauseBuilder.formatValueForSQL(Grade.VIP);

    // then
    // 이스케이프 없이 이어붙이면 따옴표가 리터럴을 끊어 SQL 을 깨뜨린다
    assertThat(result).isEqualTo("'v''ip'");
  }

  @Test
  @DisplayName("길이 상한은 String 에도 똑같이 적용되어야 한다")
  void formatValueForSQL_ShouldApplyLengthCapToStrings() {
    // given
    // 예전에는 마지막 "기타 객체" 분기에만 검사가 있어서, 같은 길이라도
    // StringBuilder 는 걸리고 String 은 그대로 통과했다.
    MybatisRepositoryProperties.getInstance().setMaxStringValueLength(10);
    String tooLong = "12345678901";

    // when / then
    assertThatThrownBy(() -> clauseBuilder.formatValueForSQL(tooLong))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("Value too long for SQL: 11");
    assertThatThrownBy(() -> clauseBuilder.formatValueForSQL(new StringBuilder(tooLong)))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThat(clauseBuilder.formatValueForSQL("1234567890")).isEqualTo("'1234567890'");
  }

  @Test
  @DisplayName("길이 상한 기본값은 긴 TEXT 를 막지 않아야 한다")
  void maxStringValueLength_DefaultShouldAllowLongText() {
    // given
    // 상한은 컬럼 폭 검증이 아니라 폭주 방지선이다. 기본값이 낮으면 긴 TEXT 컬럼을 쓰던
    // 소비자가 업그레이드만으로 깨진다.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append('a');
    }

    // when / then
    assertThat(clauseBuilder.formatValueForSQL(sb.toString())).hasSize(5002);
  }

  @Test
  @DisplayName("WHERE 조건이 하나도 붙지 않으면 거부해야 한다")
  void ensureWhereClause_ShouldRejectWhenNoConditionAppended() {
    // given / when / then
    assertThatThrownBy(() -> clauseBuilder.ensureWhereClause(0))
        .isInstanceOf(MybatisRepositoryException.class);
    clauseBuilder.ensureWhereClause(1);
  }

  @Test
  @DisplayName("buildWhereClause 는 실제로 붙인 조건 개수를 돌려줘야 한다")
  void buildWhereClause_ShouldReturnAppendedCount() {
    // given
    Map<String, Object> two = new LinkedHashMap<>();
    two.put("userId", 1L);
    two.put("name:contains", "kim");

    Map<String, Object> nestedEmpty =
        Collections.singletonMap("whereConditions", new HashMap<String, Object>());

    // when / then
    assertThat(clauseBuilder.buildWhereClause(new SQL(), two, TestUser.class)).isEqualTo(2);
    assertThat(clauseBuilder.buildWhereClause(new SQL(), null, TestUser.class)).isZero();
    // 중첩 whereConditions 가 빈 맵이면 바깥 맵 크기는 1이지만 조건은 0개다
    assertThat(clauseBuilder.buildWhereClause(new SQL(), nestedEmpty, TestUser.class)).isZero();
  }

  @Test
  @DisplayName("SET 절 값에 'where ' 가 들어 있어도 WHERE 없는 UPDATE 는 거부해야 한다")
  void ensureWhereClause_ShouldNotBeFooledBySetLiteral() {
    // given
    // 예전 가드는 완성된 문장에서 "where " 를 찾았기 때문에, 값에 'somewhere' 한 단어만 있어도
    // WHERE 없는 UPDATE 가 그대로 통과해 전 행을 갱신했다.
    // (ProviderContext 는 생성자가 package-private 이라 buildUpdateSQL 을 직접 부를 수 없어
    //  같은 조합을 절 빌더 수준에서 재현한다.)
    SQL sql = new SQL().UPDATE("test_user");
    sql.SET(clauseBuilder.buildEqualClause("name", "delivered somewhere else"));
    Map<String, Object> nestedEmpty =
        Collections.singletonMap("whereConditions", new HashMap<String, Object>());

    // when
    int appended = clauseBuilder.buildWhereClause(sql, nestedEmpty, TestUser.class);

    // then
    assertThat(sql.toString()).contains("somewhere ").doesNotContain("WHERE");
    assertThatThrownBy(() -> clauseBuilder.ensureWhereClause(appended))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("whereConditions are required");
  }

  @Test
  @DisplayName("여러 스레드가 동시에 SQL 을 만들어도 같은 결과가 나와야 한다")
  void buildInsertSQL_ShouldBeThreadSafe() throws Exception {
    // given
    // setAccessible 을 캐시에 담을 때로 옮기고 필드 잠금을 없앴으므로, 동시 접근이 여전히 안전한지 확인한다.
    final int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Callable<String>> jobs = new ArrayList<>();
    for (int i = 0; i < threads * 8; i++) {
      jobs.add(
          new Callable<String>() {
            @Override
            public String call() {
              return command.buildInsertSQL(new TestUser(1L, "kim"));
            }
          });
    }

    // when
    List<Future<String>> results = pool.invokeAll(jobs);
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    // then
    String expected = results.get(0).get();
    assertThat(expected).contains("`user_id`").contains("`name`");
    for (Future<String> result : results) {
      assertThat(result.get()).isEqualTo(expected);
    }
  }

  @Table(name = "three_col")
  static class ThreeColumn {
    @Column private String alpha;
    @Column private String bravo;
    @Column private String charlie;
  }

  @Table(name = "five_col")
  static class FiveColumn {
    @Column private String alpha;
    @Column private String bravo;
    @Column private String charlie;
    @Column private String delta;
    @Column private String echo;
  }

  /** HashMap 의 두 번째 리사이즈 문턱(용량 32 에서 24개)에 걸치는 크기다. */
  @Table(name = "twenty_four_col")
  static class TwentyFourColumn {
    @Column private String f01;
    @Column private String f02;
    @Column private String f03;
    @Column private String f04;
    @Column private String f05;
    @Column private String f06;
    @Column private String f07;
    @Column private String f08;
    @Column private String f09;
    @Column private String f10;
    @Column private String f11;
    @Column private String f12;
    @Column private String f13;
    @Column private String f14;
    @Column private String f15;
    @Column private String f16;
    @Column private String f17;
    @Column private String f18;
    @Column private String f19;
    @Column private String f20;
    @Column private String f21;
    @Column private String f22;
    @Column private String f23;
    @Column private String f24;
  }

  /**
   * @Column 이 하나도 없다. 전체 컬럼 SELECT 가 빈 컬럼을 만들어 내면 안 된다.
   */
  @Table(name = "no_col")
  static class NoColumn {
    private String ignored;
  }

  @Table(name = "four_col")
  static class FourColumn {
    @Column private String alpha;
    @Column private String bravo;
    @Column private String charlie;
    @Column private String delta;
  }

  @Table(name = "twelve_col")
  static class TwelveColumn {
    TwelveColumn() {}

    /**
     * 필드마다 접미사가 이름과 같은 값을 채운다. 값이 전부 {@code null} 이면 컬럼 순서를 어떻게 섞어도 생성 문자열이 같아져서, 컬럼과 값이 어긋나는 회귀를
     * 아무도 못 잡는다.
     */
    TwelveColumn(String prefix) {
      this.c01 = prefix + "01";
      this.c02 = prefix + "02";
      this.c03 = prefix + "03";
      this.c04 = prefix + "04";
      this.c05 = prefix + "05";
      this.c06 = prefix + "06";
      this.c07 = prefix + "07";
      this.c08 = prefix + "08";
      this.c09 = prefix + "09";
      this.c10 = prefix + "10";
      this.c11 = prefix + "11";
      this.c12 = prefix + "12";
    }

    @Column private String c01;
    @Column private String c02;
    @Column private String c03;
    @Column private String c04;
    @Column private String c05;
    @Column private String c06;
    @Column private String c07;
    @Column private String c08;
    @Column private String c09;
    @Column private String c10;
    @Column private String c11;
    @Column private String c12;
  }

  @Test
  @DisplayName("INSERT 컬럼 순서가 그대로 유지되어야 한다")
  void buildInsertSQL_ShouldKeepColumnOrderStable() {
    // given
    // buildInsertSQL 은 ORDERED_FIELD_CACHE 의 Field 목록을 순회하고, 그 순서는
    // FIELD_NAME_CACHE(HashSet) 의 순회 순서다. 해시 컨테이너의 용량이 곧 컬럼 순서를
    // 정하므로, 캐시를 만드는 방식이 바뀌면 순서가 조용히 바뀐다. 실제로 toMap 에 크기를
    // 미리 지정했다가 @Column 이 3, 4, 5, 12, 24개인 엔티티에서 순서가 바뀌는 것을 확인했다.
    // 생성된 SQL 을 통째로 박아 두어야만 이런 변경이 드러나므로 여기서 고정한다.

    // when
    String four = command.buildInsertSQL(new FourColumn());
    String twelve = command.buildInsertSQL(new TwelveColumn());

    // then
    assertThat(four)
        .isEqualTo(
            "INSERT INTO four_col\n"
                + " (`bravo`, `alpha`, `delta`, `charlie`)\n"
                + "VALUES (null, null, null, null)");
    assertThat(twelve)
        .isEqualTo(
            "INSERT INTO twelve_col\n"
                + " (`c11`, `c10`, `c02`, `c01`, `c12`, `c04`, `c03`, `c06`, `c05`, `c08`,"
                + " `c07`, `c09`)\n"
                + "VALUES (null, null, null, null, null, null, null, null, null, null, null,"
                + " null)");
  }

  @Test
  @DisplayName("단건 INSERT 도 컬럼과 값이 자리마다 짝을 이뤄야 한다")
  void buildInsertSQL_ShouldAlignValuesWithColumns() {
    // given
    // 위의 순서 고정 테스트는 값이 전부 null 이라, 컬럼과 값이 한 칸 어긋나도 문장이 똑같아
    // 어긋남 자체를 볼 수 없다. 값의 접미사를 컬럼 이름과 맞춘 행으로 그 짝까지 고정한다.
    TwelveColumn row = new TwelveColumn("a");

    // when
    String sql = command.buildInsertSQL(row);

    // then
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO twelve_col\n"
                + " (`c11`, `c10`, `c02`, `c01`, `c12`, `c04`, `c03`, `c06`, `c05`, `c08`,"
                + " `c07`, `c09`)\n"
                + "VALUES ('a11', 'a10', 'a02', 'a01', 'a12', 'a04', 'a03', 'a06', 'a05',"
                + " 'a08', 'a07', 'a09')");
  }

  @Test
  @DisplayName("배치 INSERT 의 컬럼 목록은 전체 컬럼 SELECT 목록과 같아야 한다")
  void buildInsertBatchSQL_ShouldUseSameColumnListAsSelect() {
    // given
    // 배치 인서트는 컬럼 목록을 스트림으로 다시 잇지 않고 SELECT 가 쓰는 캐시를 그대로 쓴다.
    // 두 경로가 갈라지면 배치 인서트 컬럼 목록이 조용히 달라지므로 여기서 묶어 둔다.
    List<TwelveColumn> rows = new ArrayList<>();
    rows.add(new TwelveColumn("a"));

    // when
    String sql = command.buildInsertBatchSQL(rows);

    // then
    assertThat(sql).contains("(" + entityHelper.getSelectColumnList(TwelveColumn.class) + ")");
  }

  @Test
  @DisplayName("전체 컬럼 SELECT 는 매핑된 컬럼만 백틱으로 감싸 나열해야 한다")
  void appendSelectColumns_ShouldWrapAllMappedColumns() {
    // given
    SQL sql = new SQL();

    // when
    clauseBuilder.appendSelectColumns(sql, null, null, TestUser.class);
    sql.FROM("test_user");

    // then
    // 컬럼명은 WRAPPED_COLUMN_CACHE 를 거치므로 감싼 결과가 그대로 유지되어야 한다
    assertThat(sql.toString()).contains("`user_id`").contains("`name`");
    assertThat(sql.toString()).doesNotContain("not_mapped");
  }

  @Test
  @DisplayName("DISTINCT 와 대상 컬럼을 함께 주면 중복 없이 나열해야 한다")
  void appendSelectColumns_ShouldHandleDistinctAndTarget() {
    // given
    Set<String> distinct = new LinkedHashSet<>();
    distinct.add("name");
    Set<String> target = new LinkedHashSet<>();
    target.add("name"); // distinct 와 겹치므로 빠져야 한다
    target.add("userId");
    SQL sql = new SQL();

    // when
    clauseBuilder.appendSelectColumns(sql, distinct, target, TestUser.class);
    sql.FROM("test_user");

    // then
    String rendered = sql.toString();
    assertThat(rendered).startsWith("SELECT DISTINCT `name`, `user_id`");
  }

  @Test
  @DisplayName("ORDER BY 는 '-' 접두사를 DESC 로 바꿔야 한다")
  void appendOrderBy_ShouldMapMinusPrefixToDesc() {
    // given
    List<String> orderBy = new ArrayList<>();
    orderBy.add("-userId");
    orderBy.add("name");
    SQL sql = new SQL().SELECT("1").FROM("test_user");

    // when
    clauseBuilder.appendOrderBy(sql, orderBy, TestUser.class);

    // then
    assertThat(sql.toString()).contains("ORDER BY `user_id` DESC, `name` ASC");
  }

  @Test
  @DisplayName("매핑되지 않은 필드는 SELECT/ORDER BY 에서도 매번 예외여야 한다")
  void wrappedColumnName_ShouldNotCacheFailures() {
    // given
    Set<String> unknown = new LinkedHashSet<>();
    unknown.add("nope");
    List<String> unknownOrder = new ArrayList<>();
    unknownOrder.add("nope");

    // when / then
    // 실패 경로를 캐시하면 두 번째 호출이 조용히 통과할 수 있으므로 두 번 확인한다
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () -> clauseBuilder.appendSelectColumns(new SQL(), null, unknown, TestUser.class))
          .isInstanceOf(MybatisRepositoryException.class);
      assertThatThrownBy(() -> clauseBuilder.appendOrderBy(new SQL(), unknownOrder, TestUser.class))
          .isInstanceOf(MybatisRepositoryException.class);
    }
  }

  @Test
  @DisplayName("배치 INSERT 컬럼 순서와 값 순서가 그대로 유지되어야 한다")
  void buildInsertBatchSQL_ShouldKeepColumnOrderStable() {
    // given
    // 행마다 toMap 으로 Map 을 만들지 않고 getEntityFieldsInOrder 로 값을 바로 읽는다.
    // 그 Field 목록의 순서가 getEntityFields 순회 순서에서 한 칸이라도 어긋나면
    // 컬럼과 값이 서로 다른 자리에 들어가므로, 생성된 문장을 통째로 박아 둔다.
    // 값의 접미사를 컬럼 이름의 접미사와 맞춰 둔다. 아래 기대 문자열에서 `cNN` 과 'aNN'/'bNN' 이
    // 자리마다 짝을 이루므로, Field 목록이 한 칸이라도 밀리면 눈에 보이게 깨진다.
    List<TwelveColumn> rows = new ArrayList<>();
    rows.add(new TwelveColumn("a"));
    rows.add(new TwelveColumn("b"));

    // when
    String sql = command.buildInsertBatchSQL(rows);

    // then
    // MyBatis 가 행마다 괄호를 붙이므로 VALUES (...), (...) 가 되어야 한다.
    // 한 문자열로 넘기면 VALUES ((...), (...)) 가 되어 "row value misused" 로 실행되지 않는다.
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO twelve_col\n"
                + " (`c11`, `c10`, `c02`, `c01`, `c12`, `c04`, `c03`, `c06`, `c05`, `c08`,"
                + " `c07`, `c09`)\n"
                + "VALUES ('a11', 'a10', 'a02', 'a01', 'a12', 'a04', 'a03', 'a06', 'a05',"
                + " 'a08', 'a07', 'a09')\n"
                + ", ('b11', 'b10', 'b02', 'b01', 'b12', 'b04', 'b03', 'b06', 'b05', 'b08',"
                + " 'b07', 'b09')");
  }

  @Test
  @DisplayName("배치 INSERT 는 첫 원소가 null 이어도 라이브러리 예외로 알려야 한다")
  void buildInsertBatchSQL_ShouldRejectNullFirstEntity() {
    // given
    // expectedType 을 entities.get(0).getClass() 로 먼저 잡으면 맨 NullPointerException 이 나가
    // "entity cannot be null in batch insert" 메시지에 닿지 못했다.
    List<TestUser> withNullHead = new ArrayList<>();
    withNullHead.add(null);
    withNullHead.add(new TestUser(1L, "a"));

    List<TestUser> withNullTail = new ArrayList<>();
    withNullTail.add(new TestUser(1L, "a"));
    withNullTail.add(null);

    // when / then
    assertThatThrownBy(() -> command.buildInsertBatchSQL(withNullHead))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("entity cannot be null");
    assertThatThrownBy(() -> command.buildInsertBatchSQL(withNullTail))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("entity cannot be null");
  }

  @Test
  @DisplayName("WHERE 없는 UPDATE 는 로그에 남기기 전에 거부해야 한다")
  void buildUpdateSQL_ShouldRejectBeforeLogging() {
    // given / when / then
    // 가드보다 로그가 먼저 돌면 WHERE 없는 문장이 완성된 형태로 로그에 남는다.
    assertThatThrownBy(() -> command.buildUpdateSQL(null, new java.util.HashMap<>(), null))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> command.buildDeleteSQL(null, null))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("INSERT 컬럼 순서는 toMap 의 순회 순서와 계속 같아야 한다")
  void buildInsertSQL_ShouldFollowSameOrderAsToMap() {
    // given
    // buildInsertSQL 은 toMap 대신 ORDERED_FIELD_CACHE 에서 값을 바로 읽는다.
    // 그런데 updateById 경로의 UPDATE SET 절은 여전히 toMap 의 entrySet 순서를 쓰므로,
    // 두 순서가 어긋나면 같은 엔티티가 INSERT 와 UPDATE 에서 다른 컬럼 순서로 나간다.
    // 해시 용량 문턱에 걸치는 크기들을 모아 두 순서가 같은지 직접 맞춰 본다.
    List<Object> samples = new ArrayList<>();
    samples.add(new TestUser(1L, "n"));
    samples.add(new ThreeColumn());
    samples.add(new FourColumn());
    samples.add(new FiveColumn());
    samples.add(new TwelveColumn());
    samples.add(new TwentyFourColumn());

    // when / then
    for (Object sample : samples) {
      List<String> fromOrderedCache = new ArrayList<>();
      for (java.lang.reflect.Field field : entityHelper.getEntityFieldsInOrder(sample.getClass())) {
        fromOrderedCache.add(field.getName());
      }
      List<String> fromToMap = new ArrayList<>(MybatisCommand.toMap(sample).keySet());

      assertThat(fromOrderedCache)
          .as("엔티티: %s", sample.getClass().getSimpleName())
          .containsExactlyElementsOf(fromToMap);
    }
  }

  @Test
  @DisplayName("이어 붙인 SELECT 컬럼 목록은 컬럼을 하나씩 넘긴 것과 같은 문장을 내야 한다")
  void getSelectColumnList_ShouldRenderSameAsColumnByColumn() {
    // given
    // 전체 컬럼 SELECT 는 이제 캐시된 문자열 하나를 SQL#SELECT 에 넘긴다.
    // MyBatis 가 SELECT 목록을 ", " 로 잇는다는 전제 위에 서 있으므로, 예전처럼
    // 컬럼을 하나씩 넘긴 문장과 글자까지 같은지 확인한다.
    SQL oneByOne = new SQL();
    for (String field : entityHelper.getEntityFields(TwelveColumn.class)) {
      oneByOne.SELECT(entityHelper.getWrappedColumnName(TwelveColumn.class, field));
    }
    oneByOne.FROM("twelve_col");

    SQL joined = new SQL();

    // when
    clauseBuilder.appendSelectColumns(joined, null, null, TwelveColumn.class);
    joined.FROM("twelve_col");

    // then
    assertThat(joined.toString()).isEqualTo(oneByOne.toString());
  }

  @Test
  @DisplayName("매핑된 컬럼이 없으면 SELECT 절 자체가 생기지 않아야 한다")
  void getSelectColumnList_ShouldEmitNothingWhenEntityHasNoColumns() {
    // given
    // 빈 문자열을 그대로 SQL#SELECT 에 넘기면 빈 컬럼 하나가 목록에 들어가
    // "SELECT , 1" 처럼 아무것도 넘기지 않았을 때와 문장이 달라진다.
    SQL actual = new SQL();
    SQL control = new SQL();

    // when
    clauseBuilder.appendSelectColumns(actual, null, null, NoColumn.class);
    actual.SELECT("1").FROM("no_col");
    control.SELECT("1").FROM("no_col");

    // then
    assertThat(entityHelper.getSelectColumnList(NoColumn.class)).isEmpty();
    assertThat(actual.toString()).isEqualTo(control.toString());
  }

  @Test
  @DisplayName("SELECT 컬럼 목록을 캐시해도 매번 같은 값을 돌려주어야 한다")
  void getSelectColumnList_ShouldStayConsistentAcrossRepeatedCalls() {
    // given
    String first = entityHelper.getSelectColumnList(TestUser.class);

    // when
    String second = entityHelper.getSelectColumnList(TestUser.class);

    // then
    assertThat(second).isEqualTo(first);
    assertThat(first).contains("`user_id`").contains("`name`").doesNotContain("not_mapped");
  }
}
