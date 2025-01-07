package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ExtendWith(MockitoExtension.class)
class MybatisAutoConfigurationTest {
  private static Set<String> originalExcludeFields;
  private MybatisProperties mybatisProperties;

  @Mock private Logger log;

  @InjectMocks private MybatisAutoConfiguration mybatisAutoConfiguration;

  @BeforeEach
  void setUp() {
    mybatisProperties = new MybatisProperties();
    originalExcludeFields = new HashSet<>(MybatisProperties.getExcludeFields());
  }
  //
  @AfterEach
  void tearDown() {
    mybatisProperties.setExcludeFields(originalExcludeFields);
    MybatisCommand.FIELD_CACHE.clear();
  }

  @Nested
  @DisplayName("init 메서드는")
  class InitMethod {

    @Test
    @DisplayName("PostConstruct 어노테이션이 붙은 초기화 메서드로 exclude fields를 로깅한다")
    void shouldLogExcludeFieldsOnInitialization() {
      // given
      Set<String> excludeFields = new HashSet<>();
      excludeFields.add("testField1");
      excludeFields.add("testField2");
      excludeFields = Collections.unmodifiableSet(excludeFields);
      mybatisProperties.setExcludeFields(excludeFields);

      // when
      mybatisAutoConfiguration.init();

      // then
      assertThat(MybatisProperties.getExcludeFields())
          .hasSize(2)
          .contains("testField1", "testField2");
    }
  }

  @Nested
  @DisplayName("mybatisProperties 메서드는")
  class MybatisPropertiesMethod {

    @Test
    @DisplayName("새로운 MybatisProperties 인스턴스를 생성하여 반환한다")
    void shouldCreateNewMybatisPropertiesInstance() {
      // when
      MybatisProperties result = mybatisAutoConfiguration.mybatisProperties();

      // then
      assertThat(result).isNotNull().isInstanceOf(MybatisProperties.class);
    }
  }

  @Test
  @DisplayName("Configuration 어노테이션이 적용되어 있다")
  void shouldHaveConfigurationAnnotation() {
    // when
    Configuration annotation = MybatisAutoConfiguration.class.getAnnotation(Configuration.class);

    // then
    assertThat(annotation).isNotNull();
  }

  @Test
  @DisplayName("EnableConfigurationProperties 어노테이션이 MybatisProperties를 대상으로 적용되어 있다")
  void shouldHaveEnableConfigurationPropertiesAnnotation() {
    // when
    EnableConfigurationProperties annotation =
        MybatisAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class);

    // then
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).contains(MybatisProperties.class);
  }
}
