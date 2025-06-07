package io.github.bestheroz.mybatis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  @Configuration
  @ConditionalOnClass(name = "javax.annotation.PostConstruct")
  static class JavaxPostConstructConfiguration {

    @javax.annotation.PostConstruct
    public void init() {
      LoggerFactory.getLogger(MybatisAutoConfiguration.class)
          .info("Ready to use MybatisRepository (Spring Boot 2.x)");
    }
  }

  @Configuration
  @ConditionalOnClass(name = "jakarta.annotation.PostConstruct")
  static class JakartaPostConstructConfiguration {

    @jakarta.annotation.PostConstruct
    public void init() {
      LoggerFactory.getLogger(MybatisAutoConfiguration.class)
          .info("Ready to use MybatisRepository (Spring Boot 3.x)");
    }
  }
}
