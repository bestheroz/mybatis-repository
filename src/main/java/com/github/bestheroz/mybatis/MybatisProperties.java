package com.github.bestheroz.mybatis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "mybatis-repository")
public class MybatisProperties {
  private static Set<String> excludeFields = new HashSet<>();

  public MybatisProperties() {}

  public void setExcludeFields(Set<String> excludeFields) {
    MybatisProperties.excludeFields = excludeFields;
  }

  public static Set<String> getExcludeFields() {
    return excludeFields;
  }
}
