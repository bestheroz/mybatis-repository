package io.github.bestheroz.mybatis;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  @PostConstruct
  public void init() {
    log.info("Ready to use MybatisRepository");
  }
}
