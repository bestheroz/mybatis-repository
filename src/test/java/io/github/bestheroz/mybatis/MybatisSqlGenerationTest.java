package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import io.github.bestheroz.mybatis.type.ValueEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
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
        expectedRows.append(", ");
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
    assertThat(sql).contains("INSERT INTO `test_user`");
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
  @DisplayName("WHERE 절이 없으면 거부하고, 대소문자가 섞여도 찾아내야 한다")
  void ensureWhereClause_ShouldDetectWhereRegardlessOfCase() {
    // given
    SQL withoutWhere = new SQL().DELETE_FROM("test_user");
    SQL withWhere = new SQL().DELETE_FROM("test_user").WHERE("`user_id` = 1");

    // when / then
    assertThatThrownBy(() -> clauseBuilder.ensureWhereClause(withoutWhere))
        .isInstanceOf(MybatisRepositoryException.class);
    clauseBuilder.ensureWhereClause(withWhere);
    clauseBuilder.ensureWhereClause("DELETE FROM t WHERE x = 1");
    clauseBuilder.ensureWhereClause("delete from t where x = 1");
    assertThatThrownBy(() -> clauseBuilder.ensureWhereClause("DELETE FROM t"))
        .isInstanceOf(MybatisRepositoryException.class);
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

  @Table(name = "four_col")
  static class FourColumn {
    @Column private String alpha;
    @Column private String bravo;
    @Column private String charlie;
    @Column private String delta;
  }

  @Table(name = "twelve_col")
  static class TwelveColumn {
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
    // buildInsertSQL 은 toMap 이 돌려준 HashMap 의 entrySet 을 그대로 순회하므로,
    // 그 맵의 용량이 곧 컬럼 순서를 정한다. 실제로 toMap 에 크기를 미리 지정했다가
    // @Column 이 3, 4, 5, 12, 24개인 엔티티에서 순서가 바뀌는 것을 확인했다.
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
  @DisplayName("WHERE 없는 UPDATE 는 로그에 남기기 전에 거부해야 한다")
  void buildUpdateSQL_ShouldRejectBeforeLogging() {
    // given / when / then
    // 가드보다 로그가 먼저 돌면 WHERE 없는 문장이 완성된 형태로 로그에 남는다.
    assertThatThrownBy(() -> command.buildUpdateSQL(null, new java.util.HashMap<>(), null))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> command.buildDeleteSQL(null, null))
        .isInstanceOf(MybatisRepositoryException.class);
  }
}
