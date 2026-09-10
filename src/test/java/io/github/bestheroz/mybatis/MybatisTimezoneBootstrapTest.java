package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 타임존이 <b>사용자 빈보다 먼저</b> 적용되는지 확인한다.
 *
 * <p>예전에는 자동 구성 빈의 생성자에서 값을 넣었는데, 자동 구성은 사용자 빈보다 뒤에 만들어진다. 그래서 소비자가 {@code @PostConstruct} 에서
 * 리포지토리를 호출하면 그 행들만 기본값 UTC 로 저장되고 아무 오류도 나지 않았다. 이 테스트는 그 회귀를 막는다.
 */
class MybatisTimezoneBootstrapTest {

  @AfterEach
  void tearDown() {
    MybatisRepositoryProperties.getInstance().resetToDefaults();
    ProbeConfiguration.observedAtPostConstruct = null;
  }

  @Test
  @DisplayName("사용자 빈의 @PostConstruct 시점에 이미 타임존이 적용되어 있어야 한다")
  void timezone_ShouldBeAppliedBeforeUserBeansAreCreated() {
    // given & when
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ProbeConfiguration.class)
            .web(WebApplicationType.NONE)
            .properties("mybatis-repository.timezone=Asia/Seoul")
            .run()) {

      // then
      assertThat(ProbeConfiguration.observedAtPostConstruct).isEqualTo(ZoneId.of("Asia/Seoul"));
      assertThat(MybatisRepositoryProperties.getInstance().getZoneId())
          .isEqualTo(ZoneId.of("Asia/Seoul"));
    }
  }

  @Test
  @DisplayName("프로퍼티가 없으면 기동 후에도 UTC 여야 한다")
  void timezone_ShouldStayUtcWhenPropertyAbsent() {
    // given & when
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ProbeConfiguration.class).web(WebApplicationType.NONE).run()) {

      // then
      assertThat(ProbeConfiguration.observedAtPostConstruct).isEqualTo(ZoneId.of("UTC"));
    }
  }

  @Test
  @DisplayName("알 수 없는 존 ID 는 기동 자체를 실패시켜야 한다")
  void timezone_ShouldFailStartupOnUnknownZoneId() {
    // given & when & then
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(ProbeConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .properties("mybatis-repository.timezone=Asia/Nowhere")
                    .run())
        .hasStackTraceContaining(MybatisRepositoryException.class.getName())
        .hasStackTraceContaining("Invalid timezone: Asia/Nowhere");
    assertThat(ProbeConfiguration.observedAtPostConstruct).isNull();
  }

  /**
   * {@code @PostConstruct} 대신 {@link InitializingBean} 을 쓴다. 이 라이브러리는 javax/jakarta 양쪽 네임스페이스를
   * 지원하는데 테스트 클래스패스의 Spring 은 한쪽만 처리하므로, 어느 쪽에도 기대지 않는 콜백이어야 한다.
   */
  @Configuration
  static class ProbeConfiguration implements InitializingBean {
    static ZoneId observedAtPostConstruct;

    @Override
    public void afterPropertiesSet() {
      observedAtPostConstruct = MybatisRepositoryProperties.getInstance().getZoneId();
    }
  }
}
