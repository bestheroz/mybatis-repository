package io.github.bestheroz.mybatis;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public class MybatisStringHelper {
  private static final String SEPARATOR = ":";

  // 라이브러리가 실제로 찍는 패턴은 이것 하나뿐이다. instantToString 이 값마다 불리는 자리라 미리 만들어 둔다.
  static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

  private static final DateTimeFormatter DEFAULT_DATETIME_FORMATTER =
      DateTimeFormatter.ofPattern(DEFAULT_DATETIME_FORMAT);

  // ISO_OFFSET_DATE_TIME 은 +09:00 만 받고 +0900 은 거절한다. isISO8601String 이 통과시키는 형태이므로 따로 받는다.
  private static final DateTimeFormatter OFFSET_WITHOUT_COLON =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .appendOffset("+HHmm", "Z")
          .toFormatter();

  // 설정 가능한 값들을 위한 Properties 참조
  private final MybatisRepositoryProperties properties;

  public MybatisStringHelper() {
    this(MybatisRepositoryProperties.getInstance());
  }

  public MybatisStringHelper(MybatisRepositoryProperties properties) {
    this.properties = properties != null ? properties : MybatisRepositoryProperties.getInstance();
  }

  protected String escapeSingleQuote(String src) {
    if (src == null) {
      return null;
    }
    // 이스케이프할 문자가 하나도 없는 값이 대부분이므로, 그때는 원본을 그대로 돌려주어 복사를 아낀다.
    int first = -1;
    for (int i = 0; i < src.length(); i++) {
      if (escapeOf(src.charAt(i)) != null) {
        first = i;
        break;
      }
    }
    if (first == -1) {
      return src;
    }

    // SQL injection 방지를 위한 추가 이스케이프. 한 번만 훑으면서 문자마다 독립적으로 치환하므로,
    // 치환하며 새로 넣은 역슬래시가 뒤에서 다시 치환되는 일이 없다(치환을 이어 붙이던 방식과 결과가 같다).
    final StringBuilder sb = new StringBuilder(src.length() + 16);
    sb.append(src, 0, first);
    for (int i = first; i < src.length(); i++) {
      final char c = src.charAt(i);
      final String escaped = escapeOf(c);
      if (escaped == null) {
        sb.append(c);
      } else {
        sb.append(escaped);
      }
    }
    return sb.toString();
  }

  /** 이스케이프가 필요한 문자면 바꿔 넣을 문자열을, 아니면 {@code null} 을 돌려준다. */
  private static String escapeOf(final char c) {
    switch (c) {
      case '\'':
        return "''";
      case '\\':
        return "\\\\";
      case '\0':
        return "\\0";
      case '\n':
        return "\\n";
      case '\r':
        return "\\r";
      case '\t':
        return "\\t";
      case '\b':
        return "\\b";
      case '\f':
        return "\\f";
      case '"':
        return "\\\"";
      case '\u001A':
        return "\\Z";
      default:
        return null;
    }
  }

  protected String substringBetween(String str, String open, String close) {
    if (str == null) {
      return null;
    }
    int start = str.indexOf(open);
    if (start != -1) {
      start += open.length();
      int end = str.indexOf(close, start);
      if (end != -1) {
        return str.substring(start, end);
      }
    }
    return null;
  }

  protected String substringBefore(String str) {
    if (str == null) {
      return "";
    }
    int pos = str.indexOf(SEPARATOR);
    if (pos == -1) {
      return str;
    }
    return str.substring(0, pos);
  }

  protected String substringAfter(String str) {
    if (str == null) {
      return "";
    }
    int pos = str.indexOf(SEPARATOR);
    if (pos == -1) {
      return "";
    }
    return str.substring(pos + SEPARATOR.length());
  }

  /**
   * 간단히 yyyy-MM-ddTHH:mm:ss 뒤에 오프셋이 붙은 형태인지 판별한다. 날짜의 하이픈 2개에 음수 오프셋의 하이픈 1개가 더 붙을 수 있으므로 2~3개를 모두
   * 받는다.
   *
   * <p>여기는 값싼 사전 필터일 뿐이고 최종 판정은 {@link #parseIso8601(String)} 이 한다. 통과시켰는데 파싱이 실패하면 평범한 문자열로
   * 되돌아가므로, 다소 느슨해도 엉뚱한 시각 리터럴이 SQL 에 들어가지는 않는다.
   */
  protected boolean isISO8601String(final String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    // toCharArray() 는 값마다 문자열 사본을 하나 더 만든다. 세기만 할 거라 인덱스로 훑는다.
    int countDash = 0, countColon = 0, countT = 0, countPlus = 0;
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == '-') countDash++;
      else if (c == ':') countColon++;
      else if (c == 'T') countT++;
      else if (c == '+') countPlus++;
    }
    // 예: 2025-01-02T12:34:56Z, ...+09:00, ...+0900, ...-05:00
    return countT == 1
        && (countDash == 2 || countDash == 3)
        && (countColon == 2 || countColon == 3)
        && (value.endsWith("Z") || countPlus == 1 || countDash == 3);
  }

  /**
   * 오프셋이 붙은 ISO-8601 문자열을 {@link Instant} 로 바꾼다. 사전 필터만 통과하고 실제로는 시각이 아니면 {@code null} 을 돌려주어, 호출부가
   * 평범한 문자열로 처리하게 한다.
   *
   * <p>{@code Instant#parse} 를 쓰지 않는 이유가 있다. 그 메소드가 {@code +09:00} 같은 오프셋을 받아들이는 것은 JDK 12 부터라서, 이
   * 라이브러리가 지원하는 자바 8 소비자에게는 같은 값이 예외가 된다.
   */
  protected Instant parseIso8601(final String value) {
    if (value == null) {
      return null;
    }
    final Instant withColon = parseOffsetDateTime(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    if (withColon != null) {
      return withColon;
    }
    final Instant withoutColon = parseOffsetDateTime(value, OFFSET_WITHOUT_COLON);
    if (withoutColon != null) {
      return withoutColon;
    }
    try {
      // 윤초(...T23:59:60Z)는 위 두 포맷의 STRICT 해석이 거절하고 ISO_INSTANT 만 받아 준다.
      // Z 형태라 자바 8 에서도 예외가 나지 않는다.
      return Instant.parse(value);
    } catch (final DateTimeParseException ignored) {
      return null;
    }
  }

  private Instant parseOffsetDateTime(final String value, final DateTimeFormatter formatter) {
    try {
      return OffsetDateTime.parse(value, formatter).toInstant();
    } catch (final DateTimeParseException ignored) {
      return null;
    }
  }

  protected String getCamelCaseToSnakeCase(final String str) {
    if (str == null || str.isEmpty()) {
      return str;
    }
    StringBuilder sb = new StringBuilder(str.length() * 2);
    sb.append(Character.toLowerCase(str.charAt(0)));
    for (int i = 1; i < str.length(); i++) {
      char c = str.charAt(i);
      if (Character.isUpperCase(c)) {
        sb.append('_').append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  protected String instantToString(final Instant instant, final String pattern) {
    return OffsetDateTime.ofInstant(instant, properties.getZoneId()).format(formatterOf(pattern));
  }

  /**
   * {@code DateTimeFormatter.ofPattern} 은 부를 때마다 패턴 문자열을 다시 파싱한다. 값 하나마다 거치는 자리라 라이브러리가 쓰는 패턴은 미리
   * 만들어 둔 것을 돌려주고, 그 밖의 패턴만 새로 만든다.
   */
  private static DateTimeFormatter formatterOf(final String pattern) {
    return DEFAULT_DATETIME_FORMAT.equals(pattern)
        ? DEFAULT_DATETIME_FORMATTER
        : DateTimeFormatter.ofPattern(pattern);
  }

  public static String getStackTrace(Throwable e) {
    try (StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw)) {
      e.printStackTrace(pw);
      return sw.toString();
    } catch (Exception ex) {
      return "Error generating stack trace: " + ex.getMessage();
    }
  }

  protected String wrapIdentifier(final String identifier) {
    if (identifier == null || identifier.isEmpty()) {
      throw new IllegalArgumentException("Identifier cannot be null or empty");
    }
    // SQL injection 방지를 위한 식별자 검증
    if (!isValidIdentifier(identifier)) {
      throw new IllegalArgumentException("Invalid identifier: " + identifier);
    }
    // DBMS마다 다를 수 있으나, 예시로 백틱(`)을 사용
    return "`" + identifier + "`";
  }

  // 확장된 SQL 키워드 목록 (고정 비용 절약을 위해 static).
  // 대소문자 무시 비교로 담아 두면 조회할 때마다 대문자 사본을 새로 만들지 않아도 된다.
  private static final Set<String> SQL_KEYWORDS = createSqlKeywords();

  private static Set<String> createSqlKeywords() {
    final Set<String> keywords = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    keywords.addAll(
        Arrays.asList(
            "SELECT",
            "INSERT",
            "UPDATE",
            "DELETE",
            "DROP",
            "CREATE",
            "ALTER",
            "TRUNCATE",
            "UNION",
            "OR",
            "AND",
            "WHERE",
            "FROM",
            "JOIN",
            "HAVING",
            "GROUP",
            "ORDER",
            "EXEC",
            "EXECUTE",
            "DECLARE",
            "CAST",
            "CONVERT",
            "CHAR",
            "VARCHAR",
            "NCHAR",
            "NVARCHAR",
            "SCRIPT",
            "JAVASCRIPT",
            "VBSCRIPT",
            "ONLOAD",
            "ONERROR",
            // 추가 위험 키워드
            "INFORMATION_SCHEMA",
            "SYS",
            "SYSOBJECTS",
            "SYSCOLUMNS",
            "MASTER",
            "MSDB",
            "TEMPDB",
            "MODEL",
            "XPCMDSHELL",
            "OPENROWSET",
            "OPENDATASOURCE",
            "BULK",
            "BACKUP",
            "RESTORE",
            "SHUTDOWN",
            "RECONFIGURE",
            "KILL",
            "WAITFOR",
            "DBCC",
            "USE",
            "GRANT",
            "REVOKE",
            "DENY",
            "IMPERSONATE",
            "OPENQUERY",
            "LINKED",
            "SERVER",
            "PIVOT",
            "UNPIVOT",
            "MERGE",
            "OUTPUT",
            "INSERTED",
            "DELETED",
            "CROSS",
            "APPLY",
            "OUTER",
            "INNER",
            "LEFT",
            "RIGHT",
            "FULL",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "EXISTS",
            "NOT",
            "IN",
            "LIKE",
            "BETWEEN",
            "IS",
            "NULL",
            "DISTINCT",
            "TOP",
            "PERCENT",
            "WITH",
            "TIES",
            "OFFSET",
            "FETCH",
            "NEXT",
            "ROWS",
            "ONLY",
            "PARTITION",
            "OVER",
            "ROW_NUMBER",
            "RANK",
            "DENSE_RANK",
            "NTILE",
            "LAG",
            "LEAD",
            "FIRST_VALUE",
            "LAST_VALUE"));
    return Collections.unmodifiableSet(keywords);
  }

  private boolean isValidIdentifier(String identifier) {
    // 기본 검증
    if (identifier == null || identifier.isEmpty()) {
      return false;
    }

    // 길이 제한 (SQL 식별자 최대 길이)
    if (identifier.length() > properties.getMaxIdentifierLength()) {
      return false;
    }

    // 화이트리스트 방식: 오직 알파벳으로 시작하고 알파벳/숫자/언더스코어만 포함.
    // String#matches 는 부를 때마다 Pattern 을 새로 컴파일한다. 컬럼 하나마다 거치는 자리라 직접 훑는다.
    if (!isAsciiLetter(identifier.charAt(0))) {
      return false;
    }
    for (int i = 1; i < identifier.length(); i++) {
      final char c = identifier.charAt(i);
      if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '_') {
        return false;
      }
    }

    // SQL 키워드 차단
    return !SQL_KEYWORDS.contains(identifier);
  }

  private static boolean isAsciiLetter(final char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }
}
