package io.github.bestheroz.mybatis;

import java.util.Arrays;

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
      // <>
      return String.format("`%s` <> %s", dbColumnName, builder.formatValueForSQL(value));
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
      return String.format("`%s` IS NULL", dbColumnName);
    }
  },
  IS_NOT_NULL("notNull") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      return String.format("`%s` IS NOT NULL", dbColumnName);
    }
  },
  CONTAINS("contains") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') > 0
      return String.format("INSTR(`%s`, %s) > 0", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  NOT_CONTAINS("notContains") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') = 0
      return String.format("INSTR(`%s`, %s) = 0", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  STARTS_WITH("startsWith") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // INSTR(`column`, 'value') = 1
      return String.format("INSTR(`%s`, %s) = 1", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  ENDS_WITH("endsWith") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // RIGHT(`column`, CHAR_LENGTH('value')) = 'value'
      return String.format(
          "RIGHT(`%s`, CHAR_LENGTH(%s)) = %s",
          dbColumnName, builder.formatValueForSQL(value), builder.formatValueForSQL(value));
    }
  },
  LT("lt") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // <
      return String.format("`%s` < %s", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  LTE("lte") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // <=
      return String.format("`%s` <= %s", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  GT("gt") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // >
      return String.format("`%s` > %s", dbColumnName, builder.formatValueForSQL(value));
    }
  },
  GTE("gte") {
    @Override
    public String buildClause(String dbColumnName, Object value, MybatisClauseBuilder builder) {
      // >=
      return String.format("`%s` >= %s", dbColumnName, builder.formatValueForSQL(value));
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

  public static Condition from(String code) {
    return Arrays.stream(values())
        .filter(cond -> cond.getCode().equalsIgnoreCase(code))
        .findFirst()
        .orElse(EQ); // 기본 eq
  }
}
