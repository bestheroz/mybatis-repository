package io.github.bestheroz.mybatis;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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

  // 확장된 SQL 키워드 목록 (고정 비용 절약을 위해 static)
  private static final Set<String> SQL_KEYWORDS =
      Collections.unmodifiableSet(
          new HashSet<>(
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
                  "LAST_VALUE")));

  private boolean isValidIdentifier(String identifier) {
    // 기본 검증
    if (identifier == null || identifier.isEmpty()) {
      return false;
    }

    // 길이 제한 (SQL 식별자 최대 길이)
    if (identifier.length() > MybatisRepositoryProperties.getInstance().getMaxIdentifierLength()) {
      return false;
    }

    // 화이트리스트 방식: 오직 알파벳으로 시작하고 알파벳/숫자/언더스코어만 포함
    if (!identifier.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
      return false;
    }

    // SQL 키워드 차단
    String upperIdentifier = identifier.toUpperCase();
    if (SQL_KEYWORDS.contains(upperIdentifier)) {
      return false;
    }

    // 예약어 및 위험한 패턴 추가 차단
    return !upperIdentifier.startsWith("SYS")
        && !upperIdentifier.startsWith("XP_")
        && !upperIdentifier.startsWith("SP_")
        && !upperIdentifier.contains("EXEC")
        && !upperIdentifier.contains("EVAL")
        && !upperIdentifier.contains("SCRIPT");
  }
}
