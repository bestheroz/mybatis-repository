package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프로바이더 어노테이션이 붙은 7개 추상 메소드가 실제 {@link Configuration} 에 문장으로 등록되는지 확인한다.
 *
 * <p>{@link MybatisRepository} 와 {@link MybatisNoIdRepository} 가 공유하던 838줄을 {@link
 * MybatisRepositoryBase} 로 내리면서, 어노테이션이 붙은 선언 다섯 개가 매퍼 인터페이스에서 한 단계 더 멀어졌다. MyBatis 는 매퍼를 훑을 때
 * {@code Class#getMethods} 를 쓰므로 상위 인터페이스의 메소드도 그대로 보이지만, 그건 이 라이브러리가 기대는 전제이지 라이브러리가 정하는 규칙이 아니다.
 * 전제가 깨지면 소비자 쪽에서 {@code Invalid bound statement} 로 터지고, 이 저장소에는 DB 도 샘플 앱도 없어 그때까지 아무도 모른다.
 *
 * <p>{@code default} 메소드가 문장으로 잘못 파싱되지 않는 것({@code MapperAnnotationBuilder.canHaveStatement} 가 걸러
 * 낸다)과, insert 두 개에만 붙는 {@code @Options(useGeneratedKeys = true)} 가 두 인터페이스를 실제로 갈라 놓는다는 것도 함께
 * 못박는다.
 */
class MybatisMapperRegistrationTest {

  @Table(name = "test_user")
  static class TestUser {
    @Column(name = "user_id")
    private Long id;

    @Column private String name;
  }

  interface IdMapper extends MybatisRepository<TestUser> {}

  interface NoIdMapper extends MybatisNoIdRepository<TestUser> {}

  /** insert 없이 조회/수정/삭제만 쓰는 소비자를 흉내 낸다. */
  interface BaseOnlyMapper extends MybatisRepositoryBase<TestUser> {}

  private static final List<String> SHARED_STATEMENTS =
      Arrays.asList(
          MybatisCommand.SELECT_ITEMS,
          MybatisCommand.SELECT_ITEM_BY_MAP,
          MybatisCommand.COUNT_BY_MAP,
          MybatisCommand.UPDATE_MAP_BY_MAP,
          MybatisCommand.DELETE_BY_MAP);

  private static final List<String> INSERT_STATEMENTS =
      Arrays.asList(MybatisCommand.INSERT, MybatisCommand.INSERT_BATCH);

  private static Configuration configurationWith(final Class<?> mapperInterface) {
    final Configuration configuration = new Configuration();
    configuration.addMapper(mapperInterface);
    return configuration;
  }

  private static MappedStatement statementOf(
      final Configuration configuration, final Class<?> mapperInterface, final String methodName) {
    return configuration.getMappedStatement(mapperInterface.getName() + "." + methodName, false);
  }

  @Test
  @DisplayName("두 저장소 인터페이스 모두 7개 프로바이더 메소드가 문장으로 등록되어야 한다")
  void bothRepositories_ShouldRegisterAllSevenProviderStatements() {
    // given
    for (Class<?> mapperInterface : Arrays.asList(IdMapper.class, NoIdMapper.class)) {
      // when
      final Configuration configuration = configurationWith(mapperInterface);

      // then
      // 다섯 개는 상위 인터페이스(MybatisRepositoryBase)에 선언되어 있고, 두 개는 매퍼 바로 위에 있다.
      // 상속 단계가 달라도 등록 결과는 같아야 한다.
      for (String methodName : SHARED_STATEMENTS) {
        assertThat(statementOf(configuration, mapperInterface, methodName))
            .describedAs("%s.%s 가 문장으로 등록되지 않았다", mapperInterface.getSimpleName(), methodName)
            .isNotNull();
      }
      for (String methodName : INSERT_STATEMENTS) {
        assertThat(statementOf(configuration, mapperInterface, methodName))
            .describedAs("%s.%s 가 문장으로 등록되지 않았다", mapperInterface.getSimpleName(), methodName)
            .isNotNull();
      }
    }
  }

  @Test
  @DisplayName("등록된 문장의 종류는 어노테이션이 정한 대로여야 한다")
  void registeredStatements_ShouldKeepTheirSqlCommandType() {
    // given
    final Configuration configuration = configurationWith(IdMapper.class);

    // when / then
    assertThat(
            statementOf(configuration, IdMapper.class, MybatisCommand.SELECT_ITEMS)
                .getSqlCommandType())
        .isEqualTo(SqlCommandType.SELECT);
    assertThat(
            statementOf(configuration, IdMapper.class, MybatisCommand.COUNT_BY_MAP)
                .getSqlCommandType())
        .isEqualTo(SqlCommandType.SELECT);
    assertThat(
            statementOf(configuration, IdMapper.class, MybatisCommand.INSERT).getSqlCommandType())
        .isEqualTo(SqlCommandType.INSERT);
    assertThat(
            statementOf(configuration, IdMapper.class, MybatisCommand.UPDATE_MAP_BY_MAP)
                .getSqlCommandType())
        .isEqualTo(SqlCommandType.UPDATE);
    assertThat(
            statementOf(configuration, IdMapper.class, MybatisCommand.DELETE_BY_MAP)
                .getSqlCommandType())
        .isEqualTo(SqlCommandType.DELETE);
  }

  @Test
  @DisplayName("생성 키 옵션은 MybatisRepository 쪽 insert 에만 붙어야 한다")
  void generatedKeys_ShouldOnlyApplyToIdRepository() {
    // given
    final Configuration idConfiguration = configurationWith(IdMapper.class);
    final Configuration noIdConfiguration = configurationWith(NoIdMapper.class);

    // when / then
    // 두 인터페이스의 유일한 차이가 실제로 등록 결과에 나타나는지 확인한다. 여기가 같아지면
    // 한쪽 인터페이스를 둘 이유가 없어진다.
    for (String methodName : INSERT_STATEMENTS) {
      assertThat(statementOf(idConfiguration, IdMapper.class, methodName).getKeyGenerator())
          .describedAs("IdMapper.%s 에 생성 키 옵션이 빠졌다", methodName)
          .isInstanceOf(Jdbc3KeyGenerator.class);
      assertThat(statementOf(idConfiguration, IdMapper.class, methodName).getKeyProperties())
          .containsExactly("id");

      assertThat(statementOf(noIdConfiguration, NoIdMapper.class, methodName).getKeyGenerator())
          .describedAs("NoIdMapper.%s 에 생성 키 옵션이 붙었다", methodName)
          .isInstanceOf(NoKeyGenerator.class);
    }
  }

  @Test
  @DisplayName("default 메소드는 문장으로 등록되지 않아야 한다")
  void defaultMethods_ShouldNotBecomeStatements() {
    // given
    final Configuration configuration = configurationWith(IdMapper.class);

    // when / then
    // 껍데기 37개가 문장으로 파싱되면 프로바이더가 없어 매퍼 등록 자체가 실패한다.
    // 등록에 성공했다는 것 자체가 그 증거이지만, 대표적인 이름 몇 개를 직접 확인해 둔다.
    for (String methodName : Arrays.asList("getItems", "insert", "updateById", "deleteById")) {
      assertThat(configuration.hasStatement(IdMapper.class.getName() + "." + methodName, false))
          .describedAs("default 메소드 %s 가 문장으로 등록됐다", methodName)
          .isFalse();
    }
  }

  @Test
  @DisplayName("insert 없이 공용 인터페이스만 확장해도 매퍼로 등록되고 엔티티 타입이 풀려야 한다")
  void baseOnlyMapper_ShouldRegisterAndResolveEntityClass() {
    // given / when
    assertThatCode(() -> configurationWith(BaseOnlyMapper.class)).doesNotThrowAnyException();
    final Configuration configuration = configurationWith(BaseOnlyMapper.class);

    // then
    for (String methodName : SHARED_STATEMENTS) {
      assertThat(statementOf(configuration, BaseOnlyMapper.class, methodName)).isNotNull();
    }
    for (String methodName : INSERT_STATEMENTS) {
      assertThat(
              configuration.hasStatement(BaseOnlyMapper.class.getName() + "." + methodName, false))
          .describedAs("공용 인터페이스에는 insert 가 없어야 한다")
          .isFalse();
    }

    // 프로바이더는 매퍼 타입에서 엔티티를 되찾아야 SQL 을 만들 수 있다. 공용 인터페이스를 바로
    // 확장한 매퍼도 여기서 풀리지 않으면 첫 질의에서 예외로 끝난다.
    assertThat(new MybatisEntityHelper().extractEntityClassFromMapper(BaseOnlyMapper.class))
        .isEqualTo(TestUser.class);
    assertThat(new MybatisEntityHelper().extractEntityClassFromMapper(IdMapper.class))
        .isEqualTo(TestUser.class);
    assertThat(new MybatisEntityHelper().extractEntityClassFromMapper(NoIdMapper.class))
        .isEqualTo(TestUser.class);
  }
}
