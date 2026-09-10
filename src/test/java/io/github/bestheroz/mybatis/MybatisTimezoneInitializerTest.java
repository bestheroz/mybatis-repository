package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class MybatisTimezoneInitializerTest {
  private final MybatisTimezoneInitializer initializer = new MybatisTimezoneInitializer();

  @AfterEach
  void tearDown() {
    MybatisRepositoryProperties.getInstance().resetToDefaults();
  }

  @Test
  @DisplayName("프로퍼티에 지정한 타임존이 전역 설정에 반영되어야 한다")
  void applyTimezone_ShouldBindConfiguredZoneId() {
    // given
    MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(MybatisTimezoneInitializer.TIMEZONE_PROPERTY_NAME, "Asia/Seoul");

    // when
    MybatisTimezoneInitializer.applyTimezone(environment, properties);

    // then
    assertThat(properties.getZoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
    assertThat(properties.getDateZoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
  }

  @Test
  @DisplayName("프로퍼티가 없으면 타입별 기본값을 유지해야 한다")
  void applyTimezone_ShouldKeepDefaultsWhenPropertyAbsent() {
    // given
    MybatisRepositoryProperties properties = new MybatisRepositoryProperties();

    // when
    MybatisTimezoneInitializer.applyTimezone(new MockEnvironment(), properties);

    // then
    assertThat(properties.getZoneId()).isEqualTo(ZoneId.of("UTC"));
    assertThat(properties.getDateZoneId()).isEqualTo(ZoneId.systemDefault());
  }

  @Test
  @DisplayName("알 수 없는 존 ID 는 조용히 기본값으로 떨어지지 않고 예외를 던져야 한다")
  void applyTimezone_ShouldFailFastOnUnknownZoneId() {
    // given
    MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(MybatisTimezoneInitializer.TIMEZONE_PROPERTY_NAME, "Asia/Nowhere");

    // when & then
    assertThatThrownBy(() -> MybatisTimezoneInitializer.applyTimezone(environment, properties))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("타임존을 지정하면 준비 로그가 그 값 하나만 알려야 한다")
  void readyMessage_ShouldReportSingleZoneWhenConfigured() {
    // given
    MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    properties.setTimezone("Asia/Seoul");

    // when
    String message = MybatisAutoConfiguration.readyMessage("test", properties);

    // then
    assertThat(message)
        .isEqualTo("Ready to use MybatisRepository (test), datetime literals in Asia/Seoul");
  }

  @Test
  @DisplayName("타임존 미설정이면 준비 로그가 Date 의 기본값도 함께 알려야 한다")
  void readyMessage_ShouldReportDateDefaultWhenUnset() {
    // given: 미설정이면 Instant 는 UTC, Date 는 JVM 기본 타임존이라 둘이 갈릴 수 있다
    MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
    String prefix = "Ready to use MybatisRepository (test), datetime literals in UTC";

    // when
    String message = MybatisAutoConfiguration.readyMessage("test", properties);

    // then
    if (ZoneId.of("UTC").equals(ZoneId.systemDefault())) {
      assertThat(message).isEqualTo(prefix);
    } else {
      assertThat(message).isEqualTo(prefix + " (java.util.Date in " + ZoneId.systemDefault() + ")");
    }
  }

  @Test
  @DisplayName("다른 초기화기가 프로퍼티를 얹은 뒤 읽도록 가장 낮은 우선순위여야 한다")
  void getOrder_ShouldRunLast() {
    assertThat(initializer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}
