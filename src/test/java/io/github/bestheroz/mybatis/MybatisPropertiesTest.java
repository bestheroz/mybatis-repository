package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class MybatisPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(MybatisProperties.class)
  static class TestConfig {}

  private final MybatisProperties mybatisProperties = new MybatisProperties();

  @Test
  @DisplayName("excludeFields를 설정하면 정상적으로 반영되어야 한다")
  void setExcludeFieldsShouldUpdateCorrectly() {
    // given
    Set<String> newExcludeFields = new HashSet<>();
    newExcludeFields.add("newField1");
    newExcludeFields.add("newField2");

    // when
    mybatisProperties.setExcludeFields(newExcludeFields);
    Set<String> result = MybatisProperties.getExcludeFields();

    // then
    assertThat(result).isNotNull().hasSize(2).contains("newField1", "newField2");
  }

  @Test
  @DisplayName("빈 Set으로 설정해도 정상적으로 처리되어야 한다")
  void setEmptyExcludeFieldsShouldWorkCorrectly() {
    // given
    Set<String> emptySet = new HashSet<>();

    // when
    mybatisProperties.setExcludeFields(emptySet);
    Set<String> result = MybatisProperties.getExcludeFields();

    // then
    assertThat(result).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("null을 설정하면 빈 Set이 되어야 한다")
  void setNullExcludeFieldsShouldResultInEmptySet() {
    // when
    mybatisProperties.setExcludeFields(null);
    Set<String> result = MybatisProperties.getExcludeFields();

    // then
    assertThat(result).isNotNull().isEmpty();
  }
}
