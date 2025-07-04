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
    if (src == null) {
      return null;
    }
    // SQL injection 방지를 위한 추가 이스케이프
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
    // 예: 2025-01-02T12:34:56Z or 2025-01-02T12:34:56+09:00
    return countDash == 2
        && (countColon == 2 || countColon == 3)
        && countT == 1
        && (value.endsWith("Z") || countPlus == 1);
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
    return OffsetDateTime.ofInstant(instant, ZoneId.of("UTC"))
        .format(DateTimeFormatter.ofPattern(pattern));
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

  private boolean isValidIdentifier(String identifier) {
    // 기본적인 SQL 식별자 규칙: 알파벳으로 시작, 알파벳/숫자/언더스코어만 포함
    if (!identifier.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
      return false;
    }
    // SQL 키워드 차단
    String[] sqlKeywords = {
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
      "SCRIPT"
    };
    String upperIdentifier = identifier.toUpperCase();
    for (String keyword : sqlKeywords) {
      if (upperIdentifier.equals(keyword)) {
        return false;
      }
    }
    return true;
  }
}
