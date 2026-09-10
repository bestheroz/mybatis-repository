package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;
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

  @Test
  @DisplayName("ZonedDateTime 도 설정한 타임존 기준 리터럴이어야 한다")
  void formatValueForSQL_ShouldUseConfiguredZoneIdForZonedDateTime() {
    // given: 분기가 없으면 toString() 으로 떨어져 존 이름까지 붙은 값이 SQL 에 들어간다
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
    ZonedDateTime value =
        Instant.parse("2025-01-02T12:34:56Z").atZone(ZoneId.of("America/New_York"));

    // when
    String result = builder.formatValueForSQL(value);

    // then
    assertThat(result).isEqualTo("'2025-01-02 21:34:56.000'");
  }

  @Test
  @DisplayName("java.sql.Date 는 설정한 타임존이 아니라 JVM 기본 타임존을 따라야 한다")
  void formatValueForSQL_ShouldKeepSystemDefaultForSqlDate() {
    // given: JDBC 의 "날짜만" 타입은 값이 이미 JVM 기본 타임존 자정으로 정규화되어 들어온다. UTC 20:00 은
    // 서울로는 다음 날이라, 설정을 따라갔다면 하루 밀린 값이 나온다.
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
      java.sql.Date value = new java.sql.Date(Instant.parse("2025-01-02T20:00:00Z").toEpochMilli());

      // when
      String result = builder.formatValueForSQL(value);

      // then
      assertThat(result).isEqualTo("'2025-01-02'");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  @DisplayName("java.sql.Time 도 설정한 타임존이 아니라 JVM 기본 타임존을 따라야 한다")
  void formatValueForSQL_ShouldKeepSystemDefaultForSqlTime() {
    // given: 같은 이유. 설정을 따라갔다면 9시간 밀린 05:00:00 이 나온다.
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
      java.sql.Time value = new java.sql.Time(Instant.parse("1970-01-01T20:00:00Z").toEpochMilli());

      // when
      String result = builder.formatValueForSQL(value);

      // then
      assertThat(result).isEqualTo("'20:00:00'");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  @DisplayName("OffsetTime/LocalTime 은 오프셋 없이 자리수를 고정한 시각이어야 한다")
  void formatValueForSQL_ShouldStripOffsetFromTimeOnlyTypes() {
    // given: 분기가 없으면 toString() 으로 떨어져 '12:34:56+09:00' 과 '12:34' 가 SQL 에 들어간다
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");

    // when
    String offsetTime = builder.formatValueForSQL(OffsetTime.parse("12:34:56+09:00"));
    String localTime = builder.formatValueForSQL(LocalTime.parse("12:34"));

    // then
    assertThat(offsetTime).isEqualTo("'12:34:56'");
    assertThat(localTime).isEqualTo("'12:34:00'");
  }

  @Test
  @DisplayName("java.sql.Timestamp 는 종전대로 Date 경로를 타야 한다")
  void formatValueForSQL_ShouldKeepTimestampOnDatePath() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
    java.sql.Timestamp value = java.sql.Timestamp.from(Instant.parse("2025-01-02T12:34:56Z"));

    // when
    String result = builder.formatValueForSQL(value);

    // then
    assertThat(result).isEqualTo("'2025-01-02 21:34:56.000'");
  }

  @Test
  @DisplayName("오프셋이 붙은 ISO8601 문자열도 설정한 타임존으로 옮겨야 한다")
  void formatValueForSQL_ShouldApplyZoneIdToOffsetIso8601String() {
    // given: 음수 오프셋과 콜론 없는 오프셋까지 같은 벽시계로 모여야 한다
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");

    // when
    String plusWithColon = builder.formatValueForSQL("2025-01-02T12:34:56+09:00");
    String plusWithoutColon = builder.formatValueForSQL("2025-01-02T12:34:56+0900");
    String minus = builder.formatValueForSQL("2025-01-02T12:34:56-05:00");

    // then
    assertThat(plusWithColon).isEqualTo("'2025-01-02 12:34:56.000'");
    assertThat(plusWithoutColon).isEqualTo("'2025-01-02 12:34:56.000'");
    assertThat(minus).isEqualTo("'2025-01-03 02:34:56.000'");
  }

  @Test
  @DisplayName("모양만 ISO8601 인 문자열은 예외 대신 평범한 문자열이어야 한다")
  void formatValueForSQL_ShouldFallBackWhenIso8601LookAlikeCannotParse() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");

    // when
    String result = builder.formatValueForSQL("2025-01-02T12:34:56+99:99");
    // 사전 필터를 통과하면서 따옴표까지 든 값 -- 되돌아간 경로가 이스케이프를 거치는지 확인한다
    String quoted = builder.formatValueForSQL("2025-01-02T12:34:56-05:00''");

    // then
    assertThat(result).isEqualTo("'2025-01-02T12:34:56+99:99'");
    assertThat(quoted).isEqualTo("'2025-01-02T12:34:56-05:00'''''");
  }
}
