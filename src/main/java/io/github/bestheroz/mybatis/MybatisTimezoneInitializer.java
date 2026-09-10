package io.github.bestheroz.mybatis;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * {@code mybatis-repository.timezone} 을 읽어 전역 설정에 반영한다.
 *
 * <p>자동 구성 빈의 생성자에서 하지 않는 이유가 있다. 자동 구성은 {@code DeferredImportSelector} 로 등록되어 사용자 빈보다 뒤에 만들어지므로,
 * 소비자가 생성자나 {@code @PostConstruct} 에서 리포지토리를 호출하면 그 시점의 타임존은 아직 기본값이다. 일부 행만 어긋난 채로 저장되고 아무 오류도 나지
 * 않는다. {@link ApplicationContextInitializer} 는 컨텍스트 refresh 전에 실행되므로 그 창이 없다.
 *
 * <p>{@code EnvironmentPostProcessor} 가 아니라 이 훅을 쓰는 것도 의도적이다. 그 인터페이스는 Spring Boot 4 에서 {@code
 * org.springframework.boot.env} 에서 {@code org.springframework.boot} 로 옮겨가, 어느 쪽에 맞춰 등록하든 다른 메이저에서는
 * 조용히 무시된다. {@link ApplicationContextInitializer} 는 Spring Framework 쪽 인터페이스라 Boot 2/3/4 에서 이름이 같다.
 */
public class MybatisTimezoneInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
  /** SQL 리터럴로 찍히는 시각의 기준 타임존. 지정하지 않으면 타입별 기본값을 그대로 쓴다. */
  static final String TIMEZONE_PROPERTY_NAME = "mybatis-repository.timezone";

  @Override
  public void initialize(final ConfigurableApplicationContext applicationContext) {
    applyTimezone(applicationContext.getEnvironment(), MybatisRepositoryProperties.getInstance());
  }

  /**
   * 값이 없으면 아무것도 하지 않는다(타입별 기본값 유지). 알 수 없는 존 ID 는 조용히 기본값으로 떨어지지 않고 기동을 실패시킨다 -- 시각이 통째로 어긋나는 것보다
   * 즉시 실패하는 편이 낫기 때문이다.
   */
  static void applyTimezone(
      final Environment environment, final MybatisRepositoryProperties properties) {
    if (environment == null) {
      return;
    }
    final String timezone = environment.getProperty(TIMEZONE_PROPERTY_NAME);
    if (timezone == null || timezone.trim().isEmpty()) {
      return;
    }
    properties.setTimezone(timezone);
  }

  @Override
  public int getOrder() {
    // 다른 초기화기가 프로퍼티를 더 얹을 수 있으므로 마지막에 읽는다
    return Ordered.LOWEST_PRECEDENCE;
  }
}
