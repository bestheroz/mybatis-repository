package io.github.bestheroz.mybatis;

import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MybatisAutoConfiguration implements EnvironmentAware {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  /**
   * 타임존은 {@link MybatisTimezoneInitializer} 가 refresh 전에 이미 반영해 두는 것이 정상 경로다. 다만 그 초기화기는 {@code
   * SpringApplication} 이 만든 컨텍스트에서만 실행되므로, 직접 만든 {@code AnnotationConfigApplicationContext} 처럼 그
   * 경로를 타지 않는 컨텍스트에서는 여기서 한 번 더 읽는다. 미설정이 {@code null} 이라 같은 값을 두 번 넣어도 결과가 달라지지 않는다.
   */
  @Override
  public void setEnvironment(final Environment environment) {
    MybatisTimezoneInitializer.applyTimezone(
        environment, MybatisRepositoryProperties.getInstance());
  }

  /**
   * 준비 완료 메시지. 실제 적용된 타임존을 확인용으로 함께 남긴다.
   *
   * <p>타임존을 지정하지 않으면 {@code java.util.Date} 만 JVM 기본 타임존을 쓰므로, 그럴 때는 두 값을 모두 적는다. 한쪽만 적으면 이 문제를
   * 디버깅하는 사람이 정확히 반대로 믿게 된다.
   */
  static String readyMessage(
      final String bootVersion, final MybatisRepositoryProperties properties) {
    final ZoneId zoneId = properties.getZoneId();
    final ZoneId dateZoneId = properties.getDateZoneId();
    return "Ready to use MybatisRepository ("
        + bootVersion
        + "), datetime literals in "
        + zoneId
        + (zoneId.equals(dateZoneId) ? "" : " (java.util.Date in " + dateZoneId + ")");
  }

  @Configuration
  @ConditionalOnClass(name = "javax.annotation.PostConstruct")
  static class JavaxPostConstructConfiguration {

    @javax.annotation.PostConstruct
    public void init() {
      log.info(readyMessage("Spring Boot 2.x", MybatisRepositoryProperties.getInstance()));
    }
  }

  @Configuration
  @ConditionalOnClass(name = "jakarta.annotation.PostConstruct")
  static class JakartaPostConstructConfiguration {

    @jakarta.annotation.PostConstruct
    public void init() {
      log.info(readyMessage("Spring Boot 3.x", MybatisRepositoryProperties.getInstance()));
    }
  }
}
