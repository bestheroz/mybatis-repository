package io.github.bestheroz.mybatis;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * {@code mybatis-repository.*} 설정을 읽어 전역 설정에 반영한다. 이름은 처음 용도(타임존)에서 왔고, 지금은 감사 컬럼 필드명도 함께 읽는다.
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

  /** 감사 컬럼으로 쓸 자바 필드 이름. 지정하지 않으면 기본 이름을 그대로 쓴다. */
  static final String CREATED_AT_PROPERTY_NAME = "mybatis-repository.created-at";

  static final String CREATED_BY_PROPERTY_NAME = "mybatis-repository.created-by";
  static final String UPDATED_AT_PROPERTY_NAME = "mybatis-repository.updated-at";
  static final String UPDATED_BY_PROPERTY_NAME = "mybatis-repository.updated-by";

  @Override
  public void initialize(final ConfigurableApplicationContext applicationContext) {
    final MybatisRepositoryProperties properties = MybatisRepositoryProperties.getInstance();
    applyTimezone(applicationContext.getEnvironment(), properties);
    applyAuditFieldNames(applicationContext.getEnvironment(), properties);
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

  /**
   * 감사 컬럼 필드명 넷을 반영한다. 값이 없으면 아무것도 하지 않는다(기본 이름 유지).
   *
   * <p>{@code MybatisAutoConfiguration} 도 같은 값을 한 번 더 반영한다. 타임존과 같은 구조이고 이유도 같다 -- 이 초기화기는 {@code
   * SpringApplication} 이 만든 컨텍스트에서만 돌기 때문에, 직접 만든 컨텍스트나 소비자가 공개 생성자로 인터셉터를 손수 등록한 경우에는 그쪽이 유일한 경로가
   * 된다. 미설정이 "그대로 두기" 라 두 번 반영해도 결과가 달라지지 않는다.
   */
  static void applyAuditFieldNames(
      final Environment environment, final MybatisRepositoryProperties properties) {
    if (environment == null) {
      return;
    }
    final String createdAt = auditFieldName(environment, CREATED_AT_PROPERTY_NAME, "createdAt");
    if (createdAt != null) {
      properties.setCreatedAt(createdAt);
    }
    final String createdBy = auditFieldName(environment, CREATED_BY_PROPERTY_NAME, "createdBy");
    if (createdBy != null) {
      properties.setCreatedBy(createdBy);
    }
    final String updatedAt = auditFieldName(environment, UPDATED_AT_PROPERTY_NAME, "updatedAt");
    if (updatedAt != null) {
      properties.setUpdatedAt(updatedAt);
    }
    final String updatedBy = auditFieldName(environment, UPDATED_BY_PROPERTY_NAME, "updatedBy");
    if (updatedBy != null) {
      properties.setUpdatedBy(updatedBy);
    }
  }

  /**
   * 케밥 표기와 카멜 표기를 모두 본다. {@code Environment#getProperty} 는 {@code @ConfigurationProperties} 와 달리 완화
   * 바인딩을 하지 않으므로, yaml 에 {@code createdAt} 으로 적어 둔 값을 케밥 키로만 찾으면 아무 일도 일어나지 않고 아무 오류도 나지 않는다.
   */
  private static String auditFieldName(
      final Environment environment, final String kebabCaseName, final String camelCaseSuffix) {
    final String kebabCaseValue = environment.getProperty(kebabCaseName);
    final String value =
        kebabCaseValue != null
            ? kebabCaseValue
            : environment.getProperty("mybatis-repository." + camelCaseSuffix);
    // 빈 값은 지정하지 않은 것으로 본다. 타임존 쪽과 같은 규칙이다.
    return value == null || value.trim().isEmpty() ? null : value;
  }

  @Override
  public int getOrder() {
    // 다른 초기화기가 프로퍼티를 더 얹을 수 있으므로 마지막에 읽는다
    return Ordered.LOWEST_PRECEDENCE;
  }
}
