package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MybatisStringHelperTest {
  private MybatisStringHelper helper;

  @BeforeEach
  void setUp() {
    helper = new MybatisStringHelper();
  }

  // 타임존은 전역 싱글턴에 들어가므로 테스트끼리 오염되지 않게 매번 되돌린다
  @AfterEach
  void tearDown() {
    MybatisRepositoryProperties.getInstance().resetToDefaults();
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
  @DisplayName("설정한 타임존 기준으로 Instant를 변환해야 한다")
  void instantToString_ShouldUseConfiguredZoneId() {
    // given
    MybatisRepositoryProperties.getInstance().setTimezone("Asia/Seoul");
    Instant instant = Instant.parse("2025-01-02T12:34:56Z");
    String pattern = "yyyy-MM-dd HH:mm:ss";

    // when
    String result = helper.instantToString(instant, pattern);

    // then
    assertThat(result).isEqualTo("2025-01-02 21:34:56");
  }

  @Test
  @DisplayName("알 수 없는 존 ID는 예외를 던져야 한다")
  void setTimezone_ShouldRejectUnknownZoneId() {
    // given
    MybatisRepositoryProperties properties = MybatisRepositoryProperties.getInstance();

    // when & then
    assertThatThrownBy(() -> properties.setTimezone("Asia/Nowhere"))
        .isInstanceOf(MybatisRepositoryException.class);
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

  @Test
  @DisplayName("음수 오프셋이 붙은 ISO8601 문자열도 판별해야 한다")
  void isISO8601String_ShouldAcceptNegativeOffset() {
    // given: 날짜의 하이픈 2개에 오프셋 하이픈이 하나 더 붙는다

    // when & then
    assertThat(helper.isISO8601String("2025-01-02T12:34:56-05:00")).isTrue();
    assertThat(helper.isISO8601String("2025-01-02T12:34:56-0500")).isTrue();
  }

  @Test
  @DisplayName("오프셋 표기가 달라도 같은 Instant 로 파싱해야 한다")
  void parseIso8601_ShouldAcceptEveryDetectedOffsetForm() {
    // given
    Instant expected = Instant.parse("2025-01-02T03:34:56Z");

    // when & then
    assertThat(helper.parseIso8601("2025-01-02T03:34:56Z")).isEqualTo(expected);
    assertThat(helper.parseIso8601("2025-01-02T12:34:56+09:00")).isEqualTo(expected);
    assertThat(helper.parseIso8601("2025-01-02T12:34:56+0900")).isEqualTo(expected);
    assertThat(helper.parseIso8601("2025-01-01T22:34:56-05:00")).isEqualTo(expected);
    assertThat(helper.parseIso8601("2025-01-01T22:34:56-0500")).isEqualTo(expected);
  }

  @Test
  @DisplayName("윤초가 붙은 값도 예외 없이 파싱해야 한다")
  void parseIso8601_ShouldAcceptLeapSecond() {
    // given: ISO_OFFSET_DATE_TIME 의 STRICT 해석은 60 초를 거절하고 ISO_INSTANT 만 받아 준다

    // when & then
    assertThat(helper.parseIso8601("2016-12-31T23:59:60Z"))
        .isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
  }

  @Test
  @DisplayName("파싱할 수 없는 값은 예외 대신 null 이어야 한다")
  void parseIso8601_ShouldReturnNullWhenNotATime() {
    // when & then
    assertThat(helper.parseIso8601("2025-01-02T12:34:56+99:99")).isNull();
    assertThat(helper.parseIso8601("not a time")).isNull();
    assertThat(helper.parseIso8601(null)).isNull();
  }

  /** 리팩터링 전의 구현. 한 번 훑는 방식이 이것과 글자 하나까지 같은 결과를 내는지 비교하는 기준으로만 쓴다. */
  private static String escapeByChainedReplace(final String src) {
    return src.replace("'", "''")
        .replace("\\", "\\\\")
        .replace("\0", "\\0")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\"", "\\\"")
        .replace("\u001A", "\\Z");
  }

  @Test
  @DisplayName("한 번 훑는 이스케이프가 기존 치환 연쇄와 완전히 같은 결과를 내야 한다")
  void escapeSingleQuote_ShouldMatchPreviousImplementation() {
    // given
    // 이스케이프는 SQL 인젝션을 막는 관문이므로, 성능을 이유로 바꾼 구현이
    // 어떤 입력에서도 예전과 다른 문자열을 내놓지 않는다는 것을 보여야 한다.
    final char[] interesting = {
      '\'', '\\', '\0', '\n', '\r', '\t', '\b', '\f', '"', '\u001A', 'a', '0', ' ', '가', '%', '`'
    };
    final List<String> corpus = new ArrayList<>();
    corpus.add("");
    corpus.add("평범한 값");
    corpus.add("O'Brien");
    corpus.add("C:\\path\\to");
    corpus.add("\\'");
    corpus.add("'\\");
    corpus.add("\\\n");
    corpus.add("\n\\");
    for (char first : interesting) {
      corpus.add(String.valueOf(first));
      for (char second : interesting) {
        corpus.add(new String(new char[] {first, second}));
        for (char third : interesting) {
          corpus.add(new String(new char[] {first, second, third}));
        }
      }
    }

    // when / then
    for (String input : corpus) {
      assertThat(helper.escapeSingleQuote(input))
          .as("입력: %s", java.util.Arrays.toString(input.toCharArray()))
          .isEqualTo(escapeByChainedReplace(input));
    }
  }

  @Test
  @DisplayName("이스케이프할 문자가 없으면 원본 인스턴스를 그대로 돌려주어야 한다")
  void escapeSingleQuote_ShouldReturnSameInstanceWhenNothingToEscape() {
    // given
    String clean = "plain_value_123 가나다";

    // when
    String result = helper.escapeSingleQuote(clean);

    // then
    assertThat(result).isSameAs(clean);
  }

  @Test
  @DisplayName("식별자 검증은 허용 문자와 SQL 키워드 규칙을 그대로 지켜야 한다")
  void wrapIdentifier_ShouldKeepValidationRules() {
    // given / when / then
    // 이 자리는 평범한 엔티티로도 닿는다 -- @Column(name = "left") 하나면 getItems() 가 여기서 끝난다.
    // 그래서 맨 IllegalArgumentException 이 아니라 라이브러리 공통 예외로 나가야 한다.
    assertThat(helper.wrapIdentifier("user_id")).isEqualTo("`user_id`");
    assertThat(helper.wrapIdentifier("a1")).isEqualTo("`a1`");

    // 알파벳으로 시작하지 않거나 허용되지 않는 문자가 섞이면 거부
    assertThatThrownBy(() -> helper.wrapIdentifier("1abc"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("_abc"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("a-b"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("a b"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("a`b"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("가나다"))
        .isInstanceOf(MybatisRepositoryException.class);

    // SQL 키워드는 대소문자를 가리지 않고 차단
    assertThatThrownBy(() -> helper.wrapIdentifier("SELECT"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("select"))
        .isInstanceOf(MybatisRepositoryException.class);
    assertThatThrownBy(() -> helper.wrapIdentifier("SeLeCt"))
        .isInstanceOf(MybatisRepositoryException.class);
  }

  @Test
  @DisplayName("따옴표까지 한 번에 붙이는 경로가 기존 표현식과 완전히 같은 리터럴을 내야 한다")
  void quoteAndEscape_ShouldMatchQuotedEscapeSingleQuote() {
    // given
    // quoteAndEscape 는 "'" + escapeSingleQuote(v) + "'" 를 대체하는 자리다.
    // 이스케이프 관문을 우회하지 않았음을 같은 말뭉치로 확인한다.
    final char[] interesting = {
      '\'', '\\', '\0', '\n', '\r', '\t', '\b', '\f', '"', '\u001A', 'a', '0', ' ', '가', '%', '`'
    };
    final List<String> corpus = new ArrayList<>();
    corpus.add("");
    corpus.add("평범한 값");
    corpus.add("O'Brien");
    corpus.add("C:\\path\\to");
    for (char first : interesting) {
      corpus.add(String.valueOf(first));
      for (char second : interesting) {
        corpus.add(new String(new char[] {first, second}));
        for (char third : interesting) {
          corpus.add(new String(new char[] {first, second, third}));
        }
      }
    }

    // when / then
    for (String input : corpus) {
      assertThat(helper.quoteAndEscape(input))
          .as("입력: %s", java.util.Arrays.toString(input.toCharArray()))
          .isEqualTo("'" + helper.escapeSingleQuote(input) + "'");
    }
  }

  @Test
  @DisplayName("null 값은 예전처럼 'null' 리터럴이 되어야 한다")
  void quoteAndEscape_ShouldRenderNullAsQuotedNull() {
    // given / when / then
    // ValueEnum.getValue() 가 null 을 주면 예전 표현식은 문자열 이어붙이기로 'null' 이 됐다.
    // 값이 바뀌면 저장되는 내용이 달라지므로 그대로 맞춘다.
    assertThat(helper.quoteAndEscape(null)).isEqualTo("'null'");
  }

  @Test
  @DisplayName("길이 사전 검사는 어떤 파서도 못 읽는 값만 걸러내야 한다")
  void isISO8601String_ShouldOnlyRejectLengthsNoParserAccepts() {
    // given
    // 오프셋까지 갖춘 가장 짧은 형태가 20자다. 이보다 짧으면서 문자 개수 조건을 만족하는 값은
    // 자리수가 안 맞는 값뿐이라 어차피 parseIso8601 이 null 을 돌려준다.
    String shortestParseable = "2025-01-02T12:34:56Z"; // 20자
    String nineteenCharLookalike = "2025-1-02T12:34:56Z"; // 19자, 영 padding 없음
    String withNanosAndOffset = "2025-01-02T12:34:56.123456789+09:00"; // 35자

    // when / then
    assertThat(shortestParseable).hasSize(20);
    assertThat(helper.isISO8601String(shortestParseable)).isTrue();
    assertThat(helper.parseIso8601(shortestParseable)).isNotNull();

    assertThat(withNanosAndOffset).hasSize(35);
    assertThat(helper.isISO8601String(withNanosAndOffset)).isTrue();
    assertThat(helper.parseIso8601(withNanosAndOffset)).isNotNull();

    // 길이 게이트가 떨어뜨리는 값은 게이트가 없어도 파싱에 실패하던 값이어야 한다
    assertThat(nineteenCharLookalike).hasSize(19);
    assertThat(helper.isISO8601String(nineteenCharLookalike)).isFalse();
    assertThat(helper.parseIso8601(nineteenCharLookalike)).isNull();
  }

  @Test
  @DisplayName("긴 텍스트 값은 전체를 훑지 않고 길이만으로 걸러져야 한다")
  void isISO8601String_ShouldRejectLongTextCheaply() {
    // given
    // 시각일 리 없는 긴 TEXT/JSON 값이 저장될 때마다 값 전체를 세던 자리다.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10000; i++) {
      sb.append("2025-01-02T12:34:56Z,");
    }
    String longText = sb.toString();

    // when / then
    assertThat(helper.isISO8601String(longText)).isFalse();
  }
}
