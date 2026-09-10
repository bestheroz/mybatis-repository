package io.github.bestheroz.mybatis;

import java.util.Map;
import java.util.TreeMap;

/** Mybatis where 절에서 사용될 조건식 식별용 Enum */
public enum Condition {
  EQ("eq") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // =
      return builder.buildEqualClause(dbColumnName, value);
    }
  },
  NE("ne") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildComparisonClause(dbColumnName, "<>", value);
    }
  },
  NOT("not") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // not == ne 와 동일
      return NE.buildClause(dbColumnName, value, builder);
    }
  },
  IN("in") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildInClause(dbColumnName, value, false);
    }
  },
  NOT_IN("notIn") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildInClause(dbColumnName, value, true);
    }
  },
  IS_NULL("null") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildNullClause(dbColumnName, false);
    }
  },
  IS_NOT_NULL("notNull") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildNullClause(dbColumnName, true);
    }
  },
  CONTAINS("contains") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') > 0
      return builder.buildInstrClause(dbColumnName, value, "> 0");
    }
  },
  NOT_CONTAINS("notContains") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') = 0
      return builder.buildInstrClause(dbColumnName, value, "= 0");
    }
  },
  STARTS_WITH("startsWith") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') = 1
      return builder.buildInstrClause(dbColumnName, value, "= 1");
    }
  },
  ENDS_WITH("endsWith") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // RIGHT(`column`, CHAR_LENGTH('value')) = 'value'
      return builder.buildEndsWithClause(dbColumnName, value);
    }
  },
  LT("lt") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildComparisonClause(dbColumnName, "<", value);
    }
  },
  LTE("lte") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildComparisonClause(dbColumnName, "<=", value);
    }
  },
  GT("gt") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildComparisonClause(dbColumnName, ">", value);
    }
  },
  GTE("gte") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return builder.buildComparisonClause(dbColumnName, ">=", value);
    }
  };

  private final String code;

  Condition(String code) {
    this.code = code;
  }

  public String getCode() {
    return this.code;
  }

  public abstract String buildClause(
      String dbColumnName, Object value, MybatisClauseBuilder builder);

  /**
   * 코드 → 조건 조회표. {@code values()} 는 부를 때마다 enum 배열을 복제하는데, 그 위에 스트림까지 얹으면 WHERE 조건 하나마다 배열 사본과
   * 람다·Optional 이 함께 생긴다. 조건 목록은 고정이므로 한 번만 만들어 둔다.
   *
   * <p>{@code toLowerCase()} 로 키를 맞추지 않는다. 그쪽은 기본 로케일을 타서 터키어 로케일이면 {@code "IN"} 이 {@code "ın"} 이
   * 되어 {@code in} 을 못 찾는다. {@code CASE_INSENSITIVE_ORDER} 는 원래 쓰던 {@code equalsIgnoreCase} 와 같은
   * 기준이면서 조회할 때 사본도 만들지 않는다.
   */
  private static final Map<String, Condition> BY_CODE = createLookup();

  private static Map<String, Condition> createLookup() {
    final Map<String, Condition> lookup = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Condition condition : values()) {
      lookup.put(condition.getCode(), condition);
    }
    return lookup;
  }

  public static Condition from(String code) {
    if (code == null) {
      return EQ; // 기본 eq
    }
    final Condition condition = BY_CODE.get(code);
    return condition != null ? condition : EQ; // 기본 eq
  }
}
