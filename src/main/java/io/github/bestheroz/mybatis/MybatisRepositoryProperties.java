package io.github.bestheroz.mybatis;

/** MyBatis Repository의 설정 가능한 프로퍼티들 Spring Boot의 @ConfigurationProperties와 함께 사용할 수 있습니다. */
public class MybatisRepositoryProperties {

  // 기본값들
  private static final int DEFAULT_MAX_IN_CLAUSE_SIZE = 1000;
  private static final int DEFAULT_MAX_STRING_VALUE_LENGTH = 4000;
  private static final int DEFAULT_MAX_IDENTIFIER_LENGTH = 256;

  // 실제 설정값들
  private int maxInClauseSize = DEFAULT_MAX_IN_CLAUSE_SIZE;
  private int maxStringValueLength = DEFAULT_MAX_STRING_VALUE_LENGTH;
  private int maxIdentifierLength = DEFAULT_MAX_IDENTIFIER_LENGTH;

  // 싱글톤 인스턴스 (Spring이 없는 환경에서 사용)
  private static final MybatisRepositoryProperties INSTANCE = new MybatisRepositoryProperties();

  public static MybatisRepositoryProperties getInstance() {
    return INSTANCE;
  }

  // Getters and Setters
  public int getMaxInClauseSize() {
    return maxInClauseSize;
  }

  public void setMaxInClauseSize(int maxInClauseSize) {
    if (maxInClauseSize <= 0) {
      throw new IllegalArgumentException("maxInClauseSize must be positive");
    }
    this.maxInClauseSize = maxInClauseSize;
  }

  public int getMaxStringValueLength() {
    return maxStringValueLength;
  }

  public void setMaxStringValueLength(int maxStringValueLength) {
    if (maxStringValueLength <= 0) {
      throw new IllegalArgumentException("maxStringValueLength must be positive");
    }
    this.maxStringValueLength = maxStringValueLength;
  }

  public int getMaxIdentifierLength() {
    return maxIdentifierLength;
  }

  public void setMaxIdentifierLength(int maxIdentifierLength) {
    if (maxIdentifierLength <= 0) {
      throw new IllegalArgumentException("maxIdentifierLength must be positive");
    }
    this.maxIdentifierLength = maxIdentifierLength;
  }

  // 기본값 복원 메서드
  public void resetToDefaults() {
    this.maxInClauseSize = DEFAULT_MAX_IN_CLAUSE_SIZE;
    this.maxStringValueLength = DEFAULT_MAX_STRING_VALUE_LENGTH;
    this.maxIdentifierLength = DEFAULT_MAX_IDENTIFIER_LENGTH;
  }

  @Override
  public String toString() {
    return "MybatisRepositoryProperties{"
        + "maxInClauseSize="
        + maxInClauseSize
        + ", maxStringValueLength="
        + maxStringValueLength
        + ", maxIdentifierLength="
        + maxIdentifierLength
        + '}';
  }
}
