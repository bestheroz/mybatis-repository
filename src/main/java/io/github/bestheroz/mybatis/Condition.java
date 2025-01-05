package io.github.bestheroz.mybatis;

import java.util.Arrays;

/** Mybatis where 절에서 사용될 조건식 식별용 Enum */
public enum Condition {
  EQ("eq") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // =
      return helper.buildEqualClause(dbColumnName, value);
    }
  },
  NE("ne") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // <>
      return String.format("`%s` <> %s", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  NOT("not") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // not == ne 와 동일
      return NE.buildClause(dbColumnName, value, helper);
    }
  },
  IN("in") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      return helper.buildInClause(dbColumnName, value, false);
    }
  },
  NOT_IN("notIn") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      return helper.buildInClause(dbColumnName, value, true);
    }
  },
  IS_NULL("null") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      return String.format("`%s` IS NULL", dbColumnName);
    }
  },
  IS_NOT_NULL("notNull") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      return String.format("`%s` IS NOT NULL", dbColumnName);
    }
  },
  CONTAINS("contains") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // INSTR(`column`, 'value') > 0
      return String.format("INSTR(`%s`, %s) > 0", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  NOT_CONTAINS("notContains") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // INSTR(`column`, 'value') = 0
      return String.format("INSTR(`%s`, %s) = 0", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  STARTS_WITH("startsWith") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // INSTR(`column`, 'value') = 1
      return String.format("INSTR(`%s`, %s) = 1", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  ENDS_WITH("endsWith") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // RIGHT(`column`, CHAR_LENGTH('value')) = 'value'
      return String.format(
          "RIGHT(`%s`, CHAR_LENGTH(%s)) = %s",
          dbColumnName, helper.formatValueForSQL(value), helper.formatValueForSQL(value));
    }
  },
  LT("lt") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // <
      return String.format("`%s` < %s", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  LTE("lte") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // <=
      return String.format("`%s` <= %s", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  GT("gt") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // >
      return String.format("`%s` > %s", dbColumnName, helper.formatValueForSQL(value));
    }
  },
  GTE("gte") {
    @Override
    public String buildClause(
        String dbColumnName, Object value, MybatisClauseBuilderHelper helper) {
      // >=
      return String.format("`%s` >= %s", dbColumnName, helper.formatValueForSQL(value));
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
      String dbColumnName, Object value, MybatisClauseBuilderHelper helper);

  public static Condition from(String code) {
    return Arrays.stream(values())
        .filter(cond -> cond.getCode().equalsIgnoreCase(code))
        .findFirst()
        .orElse(EQ); // 기본 eq
  }
}
