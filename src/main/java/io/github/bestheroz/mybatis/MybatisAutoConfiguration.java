package io.github.bestheroz.mybatis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties(MybatisProperties.class)
public class MybatisAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  @PostConstruct
  public void init() {
      log.info("Set 'exclude fields' from application.yml: {}", MybatisProperties.getExcludeFields());
  }

  @Bean
  @ConditionalOnMissingBean
  public MybatisProperties mybatisProperties() {
    return new MybatisProperties();
  }
}
