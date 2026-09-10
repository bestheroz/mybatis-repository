package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** 감사 컬럼 기능의 스프링 등록과 프로퍼티 반영을 확인한다. */
class MybatisAuditAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MybatisAutoConfiguration.class));

  @AfterEach
  void tearDown() {
    // 프로세스 전역 싱글톤이라 테스트 클래스 밖으로 새어 나간다.
    MybatisRepositoryProperties.getInstance().resetToDefaults();
  }

  @Configuration
  static class AuditorConfiguration {
    @Bean
    MybatisAuditorAware mybatisAuditorAware() {
      return () -> Optional.of("tester");
    }
  }

  @Configuration
  static class TwoAuditorConfiguration {
    @Bean
    MybatisAuditorAware firstAuditorAware() {
      return () -> Optional.of("first");
    }

    @Bean
    MybatisAuditorAware secondAuditorAware() {
      return () -> Optional.of("second");
    }
  }

  @Test
  @DisplayName("MybatisAuditorAware 빈이 없으면 인터셉터가 아무 일도 하지 않아야 한다")
  void interceptor_ShouldDoNothingWithoutAuditorAware() {
    // given & when & then
    // 빈 자체는 언제나 만들어진다 -- @ConditionalOnBean 은 다른 자동설정이 준 SPI 빈을 놓칠 수 있어 쓰지 않는다.
    // 대신 감사자가 없으면 인터셉터가 첫 줄에서 빠져나가므로, 쓰기 문장을 태워도 아무 컬럼도 채워지지 않아야 한다.
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(MybatisAuditColumnInterceptor.class);
          assertThat(
                  MybatisAuditColumnInterceptorTest.stampsAnything(
                      context.getBean(MybatisAuditColumnInterceptor.class)))
              .isFalse();
        });
  }

  @Test
  @DisplayName("MybatisAuditorAware 빈을 등록하면 인터셉터가 실제로 감사 컬럼을 채워야 한다")
  void interceptor_ShouldStampWithAuditorAware() {
    // given & when & then
    runner
        .withUserConfiguration(AuditorConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisAuditColumnInterceptor.class);
              assertThat(
                      MybatisAuditColumnInterceptorTest.stampsAnything(
                          context.getBean(MybatisAuditColumnInterceptor.class)))
                  .isTrue();
            });
  }

  @Test
  @DisplayName("MybatisAuditorAware 빈이 여럿이면 조용히 꺼지지 않고 기동을 실패시켜야 한다")
  void interceptor_ShouldFailWhenAuditorAwareIsAmbiguous() {
    // given & when & then
    runner
        .withUserConfiguration(TwoAuditorConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(MybatisRepositoryException.class)
                    .hasStackTraceContaining("MybatisAuditorAware"));
  }

  @Test
  @DisplayName("케밥 표기 프로퍼티로 감사 필드명을 바꿀 수 있어야 한다")
  void applyAuditFieldNames_ShouldBindKebabCaseProperties() {
    // given
    final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty(MybatisTimezoneInitializer.CREATED_AT_PROPERTY_NAME, "regDt")
            .withProperty(MybatisTimezoneInitializer.CREATED_BY_PROPERTY_NAME, "regId")
            .withProperty(MybatisTimezoneInitializer.UPDATED_AT_PROPERTY_NAME, "modDt")
            .withProperty(MybatisTimezoneInitializer.UPDATED_BY_PROPERTY_NAME, "modId");

    // when
    MybatisTimezoneInitializer.applyAuditFieldNames(environment, properties);

    // then
    assertThat(properties.getCreatedAt()).isEqualTo("regDt");
    assertThat(properties.getCreatedBy()).isEqualTo("regId");
    assertThat(properties.getUpdatedAt()).isEqualTo("modDt");
    assertThat(properties.getUpdatedBy()).isEqualTo("modId");
  }

  @Test
  @DisplayName("카멜 표기로 적어 둔 프로퍼티도 조용히 무시되지 않아야 한다")
  void applyAuditFieldNames_ShouldAlsoAcceptCamelCaseProperties() {
    // given: Environment#getProperty 는 완화 바인딩을 하지 않아, 키 표기가 다르면 그대로 묻혀 버린다
    final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    final MockEnvironment environment =
        new MockEnvironment().withProperty("mybatis-repository.createdAt", "regDt");

    // when
    MybatisTimezoneInitializer.applyAuditFieldNames(environment, properties);

    // then
    assertThat(properties.getCreatedAt()).isEqualTo("regDt");
  }

  @Test
  @DisplayName("프로퍼티가 없으면 기본 필드명을 유지해야 한다")
  void applyAuditFieldNames_ShouldKeepDefaultsWhenAbsent() {
    // given
    final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();

    // when
    MybatisTimezoneInitializer.applyAuditFieldNames(new MockEnvironment(), properties);

    // then
    assertThat(properties.getCreatedAt()).isEqualTo("createdAt");
    assertThat(properties.getCreatedBy()).isEqualTo("createdBy");
    assertThat(properties.getUpdatedAt()).isEqualTo("updatedAt");
    assertThat(properties.getUpdatedBy()).isEqualTo("updatedBy");
  }

  @Test
  @DisplayName("자동설정도 refresh 시점에 같은 값을 한 번 더 반영해야 한다")
  void autoConfiguration_ShouldApplyAuditFieldNamesFromEnvironment() {
    // given: 초기화기가 돌지 않는 컨텍스트(직접 만든 컨텍스트 등)를 위한 두 번째 경로다
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty(MybatisTimezoneInitializer.CREATED_AT_PROPERTY_NAME, "regDt");

    // when
    new MybatisAutoConfiguration().setEnvironment(environment);

    // then
    assertThat(MybatisRepositoryProperties.getInstance().getCreatedAt()).isEqualTo("regDt");
  }

  @Test
  @DisplayName("빈 필드명은 프로그램으로 설정할 때 예외로 끊어야 한다")
  void setAuditFieldName_ShouldRejectEmptyName() {
    // given
    final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();

    // when & then
    assertThatThrownBy(() -> properties.setCreatedAt(" "))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> properties.setUpdatedBy(null))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("resetToDefaults 는 감사 필드명도 되돌려야 한다")
  void resetToDefaults_ShouldRestoreAuditFieldNames() {
    // given
    final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    properties.setCreatedAt("regDt");
    properties.setUpdatedBy("modId");

    // when
    properties.resetToDefaults();

    // then
    assertThat(properties.getCreatedAt()).isEqualTo("createdAt");
    assertThat(properties.getUpdatedBy()).isEqualTo("updatedBy");
  }
}
