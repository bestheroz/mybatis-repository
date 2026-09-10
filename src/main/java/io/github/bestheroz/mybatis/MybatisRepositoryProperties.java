package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.time.ZoneId;

/** MyBatis Repository의 설정 가능한 프로퍼티들 Spring Boot의 @ConfigurationProperties와 함께 사용할 수 있습니다. */
public class MybatisRepositoryProperties {

  // 기본값들
  private static final int DEFAULT_MAX_IN_CLAUSE_SIZE = 1000;
  // SQL 리터럴 하나의 최대 길이. 값 하나가 문장을 통째로 부풀리는 것을 막는 폭주 방지선이지
  // 컬럼 폭에 맞춘 검증이 아니다. 예전에는 4000이었는데 그 값이 String 에는 적용되지 않아 아무도
  // 걸리지 않았고, 이제 적용하면서 긴 TEXT 컬럼을 쓰는 쪽이 업그레이드만으로 깨지지 않도록 올렸다.
  private static final int DEFAULT_MAX_STRING_VALUE_LENGTH = 1024 * 1024;
  private static final int DEFAULT_MAX_IDENTIFIER_LENGTH = 256;
  private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("UTC");

  // 실제 설정값들.
  // zoneId 와 발행 방식이 같다 -- 기동 시 한 스레드가 쓰고 질의 스레드들이 읽으므로, 쓴 값이
  // 보이도록 volatile 로 둔다. 셋 다 프로그램으로만 바꿀 수 있고 그 호출은 대개 설정 빈에서
  // 일어나는데, 그 스레드와 질의 스레드 사이에는 happens-before 가 없다.
  private volatile int maxInClauseSize = DEFAULT_MAX_IN_CLAUSE_SIZE;
  private volatile int maxStringValueLength = DEFAULT_MAX_STRING_VALUE_LENGTH;
  private volatile int maxIdentifierLength = DEFAULT_MAX_IDENTIFIER_LENGTH;

  // SQL 리터럴로 찍히는 시간 값의 기준 타임존. null 은 "설정하지 않음" 을 뜻하고, 그때는 타입별로
  // 예전과 똑같은 기본값을 쓴다(getZoneId / getDateZoneId 참고). 기동 시 한 번 설정되고 이후에는
  // 읽기만 하지만, 설정 스레드와 SQL 생성 스레드가 다르므로 가시성을 위해 volatile 로 둔다.
  private volatile ZoneId zoneId = null;

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

  /**
   * {@link java.time.Instant}, {@link java.time.OffsetDateTime}, ISO-8601 문자열을 SQL 리터럴로 찍을 때 기준이 되는
   * 타임존.
   *
   * <p>값은 SQL 문자열에 그대로 들어가므로, 이 타임존이 DB 세션/JDBC 드라이버가 쓰는 타임존과 다르면 저장된 시각과 읽어온 시각이 그 차이만큼 어긋난다. 설정하지
   * 않으면 UTC 다.
   */
  public ZoneId getZoneId() {
    return zoneId != null ? zoneId : DEFAULT_ZONE_ID;
  }

  /**
   * {@link java.util.Date}/{@link java.sql.Timestamp} 를 SQL 리터럴로 찍을 때 기준이 되는 타임존.
   *
   * <p>설정하지 않았을 때의 기본값만 {@link #getZoneId()} 와 다르다 -- 이 경로는 예전부터 JVM 기본 타임존을 써 왔고, 그대로 두지 않으면 아무
   * 설정도 하지 않은 소비자의 저장 값이 업그레이드만으로 옮겨간다. 타임존을 명시하면 두 경로가 같은 벽시계를 쓴다.
   */
  public ZoneId getDateZoneId() {
    return zoneId != null ? zoneId : ZoneId.systemDefault();
  }

  public void setZoneId(ZoneId zoneId) {
    if (zoneId == null) {
      throw new IllegalArgumentException("zoneId must not be null");
    }
    this.zoneId = zoneId;
  }

  /**
   * 타임존을 존 ID 문자열로 설정한다. {@link #setZoneId(ZoneId)} 와 이름을 나눈 것은, 같은 이름의 오버로드가 있으면 {@code
   * setZoneId(null)} 이 모호해지고 JavaBean 바인딩도 어느 쪽을 부를지 정하지 못하기 때문이다.
   *
   * @param timezone {@code Asia/Seoul} 같은 존 ID. 알 수 없는 값이면 {@link MybatisRepositoryException} 을
   *     던진다.
   */
  public void setTimezone(String timezone) {
    if (timezone == null || timezone.trim().isEmpty()) {
      throw new IllegalArgumentException("timezone must not be empty");
    }
    try {
      setZoneId(ZoneId.of(timezone.trim()));
    } catch (final MybatisRepositoryException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new MybatisRepositoryException("Invalid timezone: " + timezone, e);
    }
  }

  // 기본값 복원 메서드
  public void resetToDefaults() {
    this.maxInClauseSize = DEFAULT_MAX_IN_CLAUSE_SIZE;
    this.maxStringValueLength = DEFAULT_MAX_STRING_VALUE_LENGTH;
    this.maxIdentifierLength = DEFAULT_MAX_IDENTIFIER_LENGTH;
    this.zoneId = null;
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
        + ", zoneId="
        + (zoneId != null ? zoneId : "(unset)")
        + '}';
  }
}
