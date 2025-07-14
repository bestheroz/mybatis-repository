package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MybatisStringHelperTest {
  private MybatisStringHelper helper;

  @BeforeEach
  void setUp() {
    helper = new MybatisStringHelper();
  }

  @Test
  @DisplayName("작은따옴표 이스케이프 처리가 정상적으로 동작해야 한다")
  void escapeSingleQuote_ShouldEscapeCorrectly() {
    // given
    String input = "It's a test";

    // when
    String result = helper.escapeSingleQuote(input);

    // then
    assertThat(result).isEqualTo("It''s a test");
  }

  @Test
  @DisplayName("SQL 인젝션 방지를 위한 종합적인 이스케이프 처리가 동작해야 한다")
  void escapeSingleQuote_ShouldEscapeAllSpecialCharacters() {
    // given
    String input = "test'\\\"string\n\r\t\b\f\0\u001A";

    // when
    String result = helper.escapeSingleQuote(input);

    // then
    assertThat(result).isEqualTo("test''\\\\\\\"string\\n\\r\\t\\b\\f\\0\\Z");
  }

  @Test
  @DisplayName("두 문자열 사이의 문자열을 정상적으로 추출해야 한다")
  void substringBetween_ShouldExtractCorrectly() {
    // given
    String input = "hello<world>test";

    // when
    String result = helper.substringBetween(input, "<", ">");

    // then
    assertThat(result).isEqualTo("world");
  }

  @Test
  @DisplayName("null 입력시 substringBetween은 null을 반환해야 한다")
  void substringBetween_ShouldReturnNullForNullInput() {
    // when
    String result = helper.substringBetween(null, "<", ">");

    // then
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Open 문자열이 존재하지 않을 때 null을 반환해야 한다")
  void substringBetween_ShouldReturnNullForNoneOpenStringInput() {
    // given
    String input = "hello<world>test";

    // when
    String result = helper.substringBetween(input, "[", ">");

    // then
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Close 문자열이 존재하지 않을 때 null을 반환해야 한다")
  void substringBetween_ShouldReturnNullForNoneCloseStringInput() {
    // given
    String input = "hello<world>test";

    // when
    String result = helper.substringBetween(input, "<", "]");

    // then
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("구분자 이전 문자열을 정상적으로 추출해야 한다")
  void substringBefore_ShouldExtractCorrectly() {
    // given
    String input = "test:value";

    // when
    String result = helper.substringBefore(input);

    // then
    assertThat(result).isEqualTo("test");
  }

  @Test
  @DisplayName("null 입력시 substringBefore은 null을 반환해야 한다")
  void substringBefore_ShouldReturnNullForNullInput() {
    // when
    String result = helper.substringBefore(null);

    // then
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("없는 구분자로 substringBefore을 수행하면 입력된 input을 반환해야 한다.")
  void substringBefore_ShouldExtractInputForNoneString() {
    // given
    String input = "test-value";

    // when
    String result = helper.substringBefore(input);

    // then
    assertThat(result).isEqualTo(input);
  }

  @Test
  @DisplayName("구분자 이후 문자열을 정상적으로 추출해야 한다")
  void substringAfter_ShouldExtractCorrectly() {
    // given
    String input = "test:value";

    // when
    String result = helper.substringAfter(input);

    // then
    assertThat(result).isEqualTo("value");
  }

  @Test
  @DisplayName("null 입력시 substringAfter은 null을 반환해야 한다")
  void substringAfter_ShouldReturnNullForNullInput() {
    // when
    String result = helper.substringAfter(null);

    // then
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("없는 구분자로 substringAfter을 수행하면 입력된 input을 반환해야 한다.")
  void substringAfter_ShouldExtractInputForNoneString() {
    // given
    String input = "test-value";

    // when
    String result = helper.substringAfter(input);

    // then
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("ISO8601 형식의 문자열을 정상적으로 판별해야 한다")
  void isISO8601String_ShouldValidateCorrectly() {
    // given
    String validFormat1 = "2025-01-02T12:34:56Z";
    String validFormat2 = "2025-01-02T12:34:56+09:00";
    String validFormat3 = "2025-01-02T12:34:56+0900";
    String invalidFormat1 = "2025-01-02 12:34:56";
    String invalidFormat2 = "2025-01-02T12:34:56";
    String invalidFormat3 = "2025-01-02 12:34:56Z";
    String invalidFormat4 = "2025-01-02 12:34:56+09:00";
    String invalidFormat5 = "2025-01-02 12:34:56+0900";
    String invalidFormat6 = "20250102123456";
    String invalidFormat7 = "2025-01-02123456";
    String invalidFormat8 = "2025010212:34:56";
    String invalidFormat9 = "20250102-123456";
    String invalidFormat10 = "20250102123456Z";

    // when & then
    assertThat(helper.isISO8601String(validFormat1)).isTrue();
    assertThat(helper.isISO8601String(validFormat2)).isTrue();
    assertThat(helper.isISO8601String(validFormat3)).isTrue();
    assertThat(helper.isISO8601String(invalidFormat1)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat2)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat3)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat4)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat5)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat6)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat7)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat8)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat9)).isFalse();
    assertThat(helper.isISO8601String(invalidFormat10)).isFalse();
    assertThat(helper.isISO8601String(null)).isFalse();
    assertThat(helper.isISO8601String("")).isFalse();
  }

  @Test
  @DisplayName("카멜케이스를 스네이크케이스로 정상적으로 변환해야 한다")
  void getCamelCaseToSnakeCase_ShouldConvertCorrectly() {
    // given
    String input = "thisIsATest";

    // when
    String result = helper.getCamelCaseToSnakeCase(input);

    // then
    assertThat(result).isEqualTo("this_is_a_test");
  }

  @Test
  @DisplayName("Instant를 문자열로 정상적으로 변환해야 한다")
  void instantToString_ShouldFormatCorrectly() {
    // given
    Instant instant = Instant.parse("2025-01-02T12:34:56Z");
    String pattern = "yyyy-MM-dd HH:mm:ss";

    // when
    String result = helper.instantToString(instant, pattern);

    // then
    assertThat(result).isEqualTo("2025-01-02 12:34:56");
  }

  @Test
  @DisplayName("식별자를 정상적으로 래핑해야 한다")
  void wrapIdentifier_ShouldWrapCorrectly() {
    // given
    String identifier = "column_name";

    // when
    String result = helper.wrapIdentifier(identifier);

    // then
    assertThat(result).isEqualTo("`column_name`");
  }

  @Test
  @DisplayName("스택트레이스를 문자열로 정상적으로 변환해야 한다")
  void getStackTrace_ShouldConvertCorrectly() {
    // given
    Exception exception = new RuntimeException("Test Exception");

    // when
    String result = MybatisStringHelper.getStackTrace(exception);

    // then
    assertThat(result).contains("RuntimeException").contains("Test Exception");
  }
}
