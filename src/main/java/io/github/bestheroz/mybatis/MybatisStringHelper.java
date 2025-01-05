package io.github.bestheroz.mybatis;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MybatisStringHelper {
  private static final String SEPARATOR = ":";

  public MybatisStringHelper() {}

  protected String escapeSingleQuote(String src) {
    return src.replace("'", "''");
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

  /** 간단히 yyyy-MM-ddTHH:mm:ss+...Z 형태 판별 */
  protected boolean isISO8601String(final String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    int countDash = 0, countColon = 0, countT = 0, countPlus = 0;
    for (char c : value.toCharArray()) {
      if (c == '-') countDash++;
      if (c == ':') countColon++;
      if (c == 'T') countT++;
      if (c == '+') countPlus++;
    }
    // 예: 2023-01-02T12:34:56Z or 2023-01-02T12:34:56+09:00
    return countDash == 2
        && countColon == 2
        && countT == 1
        && (value.endsWith("Z") || countPlus == 1);
  }

  protected String getCamelCaseToSnakeCase(final String str) {
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
    return OffsetDateTime.ofInstant(instant, ZoneId.of("UTC"))
        .format(DateTimeFormatter.ofPattern(pattern));
  }

  public static String getStackTrace(Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  protected String wrapIdentifier(final String identifier) {
    // DBMS마다 다를 수 있으나, 예시로 백틱(`)을 사용
    return "`" + identifier + "`";
  }
}
