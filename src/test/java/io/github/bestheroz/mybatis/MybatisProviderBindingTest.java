package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프로바이더를 직접 부르지 않고 MyBatis 가 부르게 해서 나오는 SQL 을 확인한다.
 *
 * <p>나머지 테스트는 모두 {@code MybatisCommand} 의 메소드를 직접 부른다. 그래서 MyBatis 가 인자를 어떻게 싸서 넘기는지는 한 번도 지나가지
 * 않았고, 거기에 두 가지가 숨어 있었다.
 *
 * <p><b>하나.</b> 인자가 하나뿐인 프로바이더({@code buildSelectOneSQL}/{@code buildCountSQL}/{@code
 * buildDeleteSQL})에 MyBatis 는 사용자가 넘긴 조건 맵이 아니라 {@code ParamMap} 전체를 넘긴다. 그래서 {@code
 * buildSelectOneSQL} 앞머리의 {@code whereConditions.isEmpty()} 검사는 언제나 false 였고, {@code
 * getItemByMap(emptyMap)} 이 WHERE 없는 SELECT 로 나갔다. 돌려받을 자리는 {@code Optional} 하나인데 테이블 전체를 읽는다.
 *
 * <p><b>둘.</b> 그 {@code ParamMap} 에서 조건 맵을 되찾는 키 {@code "whereConditions"} 는 배포되는 jar 의 {@code
 * MethodParameters} 속성에서 나온다. 즉 {@code -parameters} 없이 컴파일하면 키가 {@code arg1} 이 되고, {@code
 * ParamMap#get} 은 없는 키에 {@code null} 이 아니라 {@code BindingException} 을 던지므로 여섯 개 공개 메소드가 소비자 쪽에서
 * 죽는다. build.gradle 이 그 옵션을 직접 걸고, 여기서 살아 있는지 확인한다.
 */
class MybatisProviderBindingTest {

  @Table(name = "test_user")
  static class TestUser {
    @Column(name = "user_id")
    private Long id;

    @Column private String name;
  }

  interface Repo extends MybatisRepository<TestUser> {}

  /** MyBatis 가 매퍼 호출을 프로바이더까지 실어 나르는 경로를 그대로 태워 SQL 을 얻는다. */
  private static String sqlOf(final String methodName, final Object... args) {
    final Configuration configuration = new Configuration();
    configuration.addMapper(Repo.class);
    final Object parameterObject =
        new ParamNameResolver(configuration, mapperMethod(methodName)).getNamedParams(args);
    final MappedStatement statement =
        configuration.getMappedStatement(Repo.class.getName() + "." + methodName, false);
    return statement.getBoundSql(parameterObject).getSql().replace('\n', ' ');
  }

  /** 프로바이더가 던진 예외는 MyBatis 가 {@code BuilderException} 으로 감싸므로 맨 아래 원인을 꺼낸다. */
  private static Throwable rootCauseOf(final String methodName, final Object... args) {
    try {
      sqlOf(methodName, args);
    } catch (final Throwable thrown) {
      Throwable cause = thrown;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      return cause;
    }
    throw new AssertionError(methodName + " 이 예외 없이 끝났다");
  }

  private static Method mapperMethod(final String methodName) {
    for (Method method : Repo.class.getMethods()) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    throw new AssertionError("no such mapper method: " + methodName);
  }

  @Test
  @DisplayName("배포되는 클래스에 파라미터 이름이 남아 있어야 한다")
  void compiledClasses_ShouldCarryParameterNames() {
    // given
    // 이 이름이 곧 ParamMap 의 키다. -parameters 가 빠지면 arg0/arg1 이 되고,
    // 조건 맵을 되찾지 못해 getItemById/getItemByMap/countAll/countByMap/deleteById/deleteByMap 이
    // 소비자 쪽에서 "Parameter 'whereConditions' not found" 로 죽는다.
    final Parameter[] parameters = mapperMethod("buildSelectOneSQL").getParameters();

    // when / then
    assertThat(parameters[1].isNamePresent())
        .describedAs("컴파일 옵션 -parameters 가 빠졌다. build.gradle 을 확인할 것")
        .isTrue();
    assertThat(parameters[1].getName()).isEqualTo("whereConditions");
  }

  @Test
  @DisplayName("MyBatis 를 거쳐도 조건이 있는 질의는 그대로 만들어져야 한다")
  void providerBinding_ShouldRenderExpectedSql() {
    // given
    final Map<String, Object> byId = Collections.<String, Object>singletonMap("id", 7L);

    // when / then
    assertThat(sqlOf("buildSelectOneSQL", null, byId))
        .isEqualTo("SELECT `name`, `user_id` FROM test_user WHERE (`user_id` = 7)");
    assertThat(sqlOf("buildCountSQL", null, byId))
        .isEqualTo("SELECT COUNT(1) AS CNT FROM test_user WHERE (`user_id` = 7)");
    assertThat(sqlOf("buildDeleteSQL", null, byId))
        .isEqualTo("DELETE FROM test_user WHERE (`user_id` = 7)");
    assertThat(
            sqlOf(
                "buildSelectSQL",
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                byId,
                Collections.emptyList(),
                null,
                null))
        .isEqualTo("SELECT `name`, `user_id` FROM test_user WHERE (`user_id` = 7)");
    assertThat(
            sqlOf(
                "buildUpdateSQL",
                null,
                Collections.<String, Object>singletonMap("name", "kim"),
                byId))
        .isEqualTo("UPDATE test_user SET `name` = 'kim' WHERE (`user_id` = 7)");
  }

  @Test
  @DisplayName("MyBatis 를 거친 INSERT 도 값이 null 인 컬럼을 DEFAULT 로 내야 한다")
  void insert_ShouldRenderDefaultForNullFieldsThroughMybatis() {
    // given
    // 소비자 쪽에서 터진 모양 그대로다. 값이 null 인 컬럼을 null 리터럴로 내면 NOT NULL DEFAULT 컬럼이
    // "Column 'X' cannot be null" 로 거부된다. DEFAULT 는 그 자리만 DB 기본값으로 채운다.
    final TestUser entity = new TestUser();
    entity.id = 7L;
    // name 은 null 로 둔다

    // when / then
    // 컬럼 목록은 두 개가 그대로 남고 값 자리만 갈라진다.
    assertThat(sqlOf("buildInsertSQL", entity))
        .isEqualTo("INSERT INTO test_user  (`name`, `user_id`) VALUES (DEFAULT, 7)");
  }

  @Test
  @DisplayName("조건이 빈 getItemByMap 은 테이블 전체를 읽지 말고 예외로 끝나야 한다")
  void getItemByMap_ShouldRejectEmptyConditions() {
    // given / when
    final Throwable emptyMapCause = rootCauseOf("buildSelectOneSQL", null, Collections.emptyMap());

    // then
    // 예전에는 "SELECT `name`, `user_id` FROM test_user" 가 나갔다. Optional 자리에 테이블 전체다.
    assertThat(emptyMapCause)
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("'where' Conditions is required");
  }

  @Test
  @DisplayName("조건 맵 자리에 null 을 넘기면 원인을 알 수 있는 예외로 끝나야 한다")
  void nullConditions_ShouldReportMissingWhereRatherThanUnknownField() {
    // given / when
    final Throwable selectOneCause =
        rootCauseOf("buildSelectOneSQL", null, (Map<String, Object>) null);
    final Throwable deleteCause = rootCauseOf("buildDeleteSQL", null, (Map<String, Object>) null);

    // then
    // 예전에는 ParamMap 을 통째로 조건 맵으로 읽어 그 키(context, param1, param2)를 엔티티 필드로
    // 찾다가 "entity 에 포함되지 않는 필드 발견 : context" 로 끝났다. 원인을 짐작할 수 없는 메시지다.
    assertThat(selectOneCause)
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("'where' Conditions is required");
    assertThat(deleteCause)
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("whereConditions are required");
  }

  @Test
  @DisplayName("countAll 과 조건 없는 조회는 그대로 전체를 대상으로 해야 한다")
  void countAll_ShouldStayUnfiltered() {
    // given / when / then
    // 위의 가드가 넓어져 여기까지 막으면 countAll() 이 못 쓰게 된다. 경계를 함께 못박는다.
    assertThat(sqlOf("buildCountSQL", null, Collections.emptyMap()))
        .isEqualTo("SELECT COUNT(1) AS CNT FROM test_user");
    assertThat(
            sqlOf(
                "buildSelectSQL",
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyList(),
                null,
                null))
        .isEqualTo("SELECT `name`, `user_id` FROM test_user");
  }
}
