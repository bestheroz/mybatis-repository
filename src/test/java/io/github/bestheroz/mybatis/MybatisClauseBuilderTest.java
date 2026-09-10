package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시각 값은 바인드 파라미터가 아니라 SQL 문자열로 그대로 이어붙기 때문에, 어느 타임존의 벽시계를 찍느냐가 곧 DB 에 저장되는 값이 된다. 설정한 타임존이 리터럴까지
 * 실제로 반영되는지 확인한다.
 */
class MybatisClauseBuilderTest {
  private MybatisClauseBuilder builder;

  @BeforeEach
  void setUp() {
    MybatisStringHelper stringHelper = new MybatisStringHelper();
    builder = new MybatisClauseBuilder(stringHelper, new MybatisEntityHelper(stringHelper));
  }

  @AfterEach
  void tearDown() {
    MybatisRepositoryProperties.getInstance().resetToDefaults();
  }

  @Test
  @DisplayName("타임존을 지정하지 않으면 UTC 기준 리터럴을 만들어야 한다")
  void formatValueForSQL_ShouldUseUtcByDefault() {
    // given
    Instant instant = Instant.parse("2025-01-02T12:34:56Z");

    // when
    String result = builder.formatValueForSQL(instant);

    // then
    assertThat(result).isEqualTo("'2025-01-02 12:34:56.000'");
  }

  @Test
  @DisplayName("설정한 타임존 기준으로 시각 리터럴을 만들어야 한다")
  void formatValueForSQL_ShouldUseConfiguredZoneId() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
    Instant instant = Instant.parse("2025-01-02T12:34:56Z");

    // when
    String result = builder.formatValueForSQL(instant);

    // then
    assertThat(result).isEqualTo("'2025-01-02 21:34:56.000'");
  }

  @Test
  @DisplayName("타임존을 지정하지 않으면 Date 는 종전처럼 JVM 기본 타임존을 써야 한다")
  void formatValueForSQL_ShouldKeepSystemDefaultForDateWhenUnset() {
    // given
    Date date = Date.from(Instant.parse("2025-01-02T12:34:56Z"));
    String expected =
        "'"
            + date.toInstant()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            + "'";

    // when
    String result = builder.formatValueForSQL(date);

    // then
    assertThat(result).isEqualTo(expected);
  }

  @Test
  @DisplayName("타임존을 지정하면 Date 도 같은 벽시계를 써야 한다")
  void formatValueForSQL_ShouldUseConfiguredZoneIdForDate() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
    Date date = Date.from(Instant.parse("2025-01-02T12:34:56Z"));

    // when
    String result = builder.formatValueForSQL(date);

    // then
    assertThat(result).isEqualTo("'2025-01-02 21:34:56.000'");
  }

  @Test
  @DisplayName("LocalDateTime/LocalDate 는 이미 벽시계라 타임존 설정과 무관해야 한다")
  void formatValueForSQL_ShouldNotShiftLocalTypes() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");

    // when
    String dateTime = builder.formatValueForSQL(LocalDateTime.parse("2025-01-02T12:34:56"));
    String date = builder.formatValueForSQL(LocalDate.parse("2025-01-02"));

    // then
    assertThat(dateTime).isEqualTo("'2025-01-02 12:34:56.000'");
    assertThat(date).isEqualTo("'2025-01-02'");
  }

  @Test
  @DisplayName("ISO8601 문자열로 들어온 값도 설정한 타임존을 따라야 한다")
  void formatValueForSQL_ShouldApplyZoneIdToIso8601String() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");

    // when
    String result = builder.formatValueForSQL("2025-01-02T12:34:56Z");

    // then
    assertThat(result).isEqualTo("'2025-01-02 21:34:56.000'");
  }
}
