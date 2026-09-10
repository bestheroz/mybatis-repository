package io.github.bestheroz.mybatis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  /**
   * 준비 완료 메시지. 타임존은 {@link MybatisEnvironmentPostProcessor} 가 refresh 전에 이미 반영해 두므로, 여기서는 실제 적용된 값을
   * 확인용으로 함께 남긴다.
   */
  private static String readyMessage(final String bootVersion) {
    return "Ready to use MybatisRepository ("
        + bootVersion
        + "), datetime literals in "
        + MybatisRepositoryProperties.getInstance().getZoneId();
  }

  @Configuration
  @ConditionalOnClass(name = "javax.annotation.PostConstruct")
  static class JavaxPostConstructConfiguration {

    @javax.annotation.PostConstruct
    public void init() {
      log.info(readyMessage("Spring Boot 2.x"));
    }
  }

  @Configuration
  @ConditionalOnClass(name = "jakarta.annotation.PostConstruct")
  static class JakartaPostConstructConfiguration {

    @jakarta.annotation.PostConstruct
    public void init() {
      log.info(readyMessage("Spring Boot 3.x"));
    }
  }
}
