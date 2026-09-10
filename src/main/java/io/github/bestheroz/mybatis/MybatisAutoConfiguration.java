package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MybatisAutoConfiguration implements EnvironmentAware {
  private static final Logger log = LoggerFactory.getLogger(MybatisAutoConfiguration.class);

  /**
   * 타임존과 감사 컬럼 필드명은 {@link MybatisTimezoneInitializer} 가 refresh 전에 반영해 두는 것이 정상 경로다. 다만 그 초기화기는
   * {@code SpringApplication} 이 만든 컨텍스트에서만 실행되므로, 직접 만든 {@code AnnotationConfigApplicationContext}
   * 처럼 그 경로를 타지 않는 컨텍스트에서는 여기서 한 번 더 읽는다. 미설정이 "그대로 두기" 라 같은 값을 두 번 넣어도 결과가 달라지지 않는다.
   */
  @Override
  public void setEnvironment(final Environment environment) {
    final MybatisRepositoryProperties properties = MybatisRepositoryProperties.getInstance();
    MybatisTimezoneInitializer.applyTimezone(environment, properties);
    MybatisTimezoneInitializer.applyAuditFieldNames(environment, properties);
  }

  /**
   * 감사 컬럼 인터셉터. {@link MybatisAuditorAware} 빈이 없으면 감사자 없이 만들어지고, 그 인터셉터는 아무 일도 하지 않는다.
   *
   * <p><b>{@code @ConditionalOnBean} 을 쓰지 않는다.</b> 그 조건은 조건 평가 시점에 이미 등록된 빈 정의만 보므로, 소비자가 SPI 빈을 사내
   * 스타터 같은 <i>다른 자동설정</i>에서 주면 평가 순서에 따라 놓친다. 그때 나타나는 증상이 "감사 컬럼이 조용히 안 채워짐" 이라 원인을 찾기가 아주 어렵다. 빈을
   * 언제나 만들어 두고 {@link ObjectProvider} 로 늦게 찾으면 그 순서 문제가 사라진다 -- 빈 정의는 이 팩토리 메소드가 불릴 때 이미 전부 등록되어
   * 있다. 아무 일도 하지 않는 인터셉터 하나가 체인에 붙는 비용은 무시할 만하고, 조용히 꺼지는 쪽이 훨씬 비싸다.
   *
   * <p>후보가 둘 이상이면 기동을 실패시킨다. {@code getIfUnique} 는 {@code @Primary} 가 없으면 {@code null} 을 돌려주는데, 그것을
   * 그대로 받으면 "빈을 두 개 등록했더니 기능이 통째로 꺼졌다" 가 된다.
   *
   * <p>MyBatis 가 클래스패스에 없는 컨텍스트도 있을 수 있어(모든 프레임워크 의존성이 {@code compileOnly} 다) 클래스 조건을 함께 건다. 그리고
   * 빈으로 등록하는 것만으로 충분한 것은 <b>{@code mybatis-spring-boot-autoconfigure} 가 {@code SqlSessionFactory} 를
   * 만들 때뿐이다</b> -- 그쪽이 컨텍스트의 {@code Interceptor} 빈을 모아 넣어 준다. 소비자가 {@code SqlSessionFactoryBean} 을
   * 직접 정의했다면 이 빈을 그 {@code plugins} 에 손수 넣어야 한다.
   */
  @Bean
  @ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
  @ConditionalOnMissingBean
  public MybatisAuditColumnInterceptor mybatisAuditColumnInterceptor(
      final ObjectProvider<MybatisAuditorAware> auditorAwareProvider) {
    final MybatisAuditorAware auditorAware = auditorAwareProvider.getIfUnique();
    if (auditorAware == null && auditorAwareProvider.stream().findAny().isPresent()) {
      throw new MybatisRepositoryException(
          "MybatisAuditorAware 빈이 여럿이라 감사자를 정할 수 없다. 하나만 두거나 @Primary 를 붙일 것");
    }
    if (auditorAware == null) {
      log.debug("MybatisAuditorAware 빈이 없어 감사 컬럼 기입을 켜지 않는다");
    } else {
      log.info("Ready to stamp audit columns: {}", MybatisRepositoryProperties.getInstance());
    }
    return new MybatisAuditColumnInterceptor(
        auditorAware, MybatisRepositoryProperties.getInstance());
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
