package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 감사 컬럼 인터셉터를 MyBatis 가 부르는 모양 그대로 태워 확인한다.
 *
 * <p>UPDATE 쪽은 맵만 들여다보지 않고 {@code MappedStatement#getBoundSql} 까지 돌려 실제 문장을 본다. 이 인터셉터가 고쳐야 하는 것은
 * "프로바이더가 읽는 자리" 이지 "우리가 들고 있는 맵" 이 아니어서, 맵만 확인하는 테스트는 {@code ParamMap} 별칭 키를 잘못 짚어도 통과한다.
 */
class MybatisAuditColumnInterceptorTest {

  // ===========================================
  // 픽스처
  // ===========================================

  @Table(name = "audit_entity")
  static class AuditEntity {
    @Column private Long id;
    @Column private String name;
    @Column private String memo;
    @Column private Instant createdAt;
    @Column private String createdBy;
    @Column private Instant updatedAt;
    @Column private String updatedBy;
  }

  /** 감사 컬럼이 하나도 없는 엔티티. 이런 엔티티가 있는 것이 정상이다. */
  @Table(name = "plain_entity")
  static class PlainEntity {
    @Column private Long id;
    @Column private String name;
  }

  /** 일시 필드가 {@code Instant} 가 아닌 다른 지원 타입인 경우. */
  @Table(name = "mixed_entity")
  static class MixedTypeEntity {
    @Column private LocalDateTime createdAt;
    @Column private Date updatedAt;
  }

  /** 지원하지 않는 일시 타입. */
  @Table(name = "bad_timestamp_entity")
  static class BadTimestampEntity {
    @Column private Long createdAt;
  }

  /** 지원하지 않는 감사자 타입. */
  @Table(name = "bad_auditor_entity")
  static class BadAuditorEntity {
    @Column private Instant createdAt;
    @Column private Long createdBy;
  }

  /** 필드명을 바꾼 엔티티. */
  @Table(name = "custom_named_entity")
  static class CustomNamedEntity {
    @Column private String name;
    @Column private Instant regDt;
    @Column private String regId;
  }

  /** 레거시 MyBatis 엔티티에 흔한 {@code java.sql.Timestamp}. */
  @Table(name = "timestamp_entity")
  static class TimestampEntity {
    @Column private Timestamp createdAt;
  }

  /** {@code java.sql.Date} 는 {@code toInstant()} 가 동작하지 않아 지원하지 않는다. */
  @Table(name = "sql_date_entity")
  static class SqlDateEntity {
    @Column private java.sql.Date createdAt;
  }

  /** 생성 계열 감사 컬럼만 있는 엔티티. */
  @Table(name = "created_only_entity")
  static class CreatedOnlyEntity {
    @Column private String name;
    @Column private Instant createdAt;
    @Column private String createdBy;
  }

  interface AuditRepo extends MybatisNoIdRepository<AuditEntity> {}

  interface PlainRepo extends MybatisNoIdRepository<PlainEntity> {}

  interface MixedTypeRepo extends MybatisNoIdRepository<MixedTypeEntity> {}

  interface BadTimestampRepo extends MybatisNoIdRepository<BadTimestampEntity> {}

  interface BadAuditorRepo extends MybatisNoIdRepository<BadAuditorEntity> {}

  interface CustomNamedRepo extends MybatisNoIdRepository<CustomNamedEntity> {}

  interface TimestampRepo extends MybatisNoIdRepository<TimestampEntity> {}

  interface SqlDateRepo extends MybatisNoIdRepository<SqlDateEntity> {}

  interface CreatedOnlyRepo extends MybatisNoIdRepository<CreatedOnlyEntity> {}

  /** {@link MybatisRepositoryBase} 를 확장하지 않는 매퍼. 이름만 같은 문장은 대상이 아니다. */
  interface NotARepositoryMapper {
    void buildUpdateSQL(Map<String, Object> updateMap, Map<String, Object> whereConditions);
  }

  /** 감사자를 고정으로 돌려주면서 몇 번 불렸는지 센다. */
  private static final class FixedAuditor implements MybatisAuditorAware {
    private final String auditor;
    private int calls;

    private FixedAuditor(final String auditor) {
      this.auditor = auditor;
    }

    @Override
    public Optional<String> getCurrentAuditor() {
      calls++;
      return Optional.ofNullable(auditor);
    }
  }

  private final MybatisRepositoryProperties properties = new MybatisRepositoryProperties();
  private final FixedAuditor auditor = new FixedAuditor("tester");
  private final MybatisAuditColumnInterceptor interceptor =
      new MybatisAuditColumnInterceptor(auditor, properties);

  @AfterEach
  void tearDown() {
    // 이 테스트는 자기 인스턴스만 쓰지만, 싱글톤이 프로세스 전역이라 안전망으로 되돌린다.
    MybatisRepositoryProperties.getInstance().resetToDefaults();
  }

  // ===========================================
  // 헬퍼
  // ===========================================

  private static final Method EXECUTOR_UPDATE = executorUpdateMethod();

  private static Method executorUpdateMethod() {
    try {
      return Executor.class.getMethod("update", MappedStatement.class, Object.class);
    } catch (final NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  /** {@code proceed()} 가 실제로 무언가 실행하지 않도록 아무 일도 하지 않는 Executor 를 끼운다. */
  private static Executor noopExecutor() {
    final InvocationHandler handler =
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "update":
              return Integer.valueOf(0);
            case "toString":
              return "noopExecutor";
            case "hashCode":
              return Integer.valueOf(System.identityHashCode(proxy));
            case "equals":
              return Boolean.valueOf(proxy == args[0]);
            default:
              return null;
          }
        };
    return (Executor)
        Proxy.newProxyInstance(
            Executor.class.getClassLoader(), new Class<?>[] {Executor.class}, handler);
  }

  private static MappedStatement statementOf(final String id, final SqlCommandType commandType) {
    return new MappedStatement.Builder(
            new Configuration(), id, (SqlSource) parameterObject -> null, commandType)
        .build();
  }

  private static String statementId(final Class<?> mapperInterface, final String methodName) {
    return mapperInterface.getName() + "." + methodName;
  }

  private void intercept(final MappedStatement statement, final Object parameter) {
    intercept(interceptor, statement, parameter);
  }

  private static void intercept(
      final MybatisAuditColumnInterceptor target,
      final MappedStatement statement,
      final Object parameter) {
    try {
      target.intercept(
          new Invocation(noopExecutor(), EXECUTOR_UPDATE, new Object[] {statement, parameter}));
    } catch (final RuntimeException e) {
      throw e;
    } catch (final Throwable e) {
      throw new AssertionError(e);
    }
  }

  private void insert(final Class<?> mapperInterface, final Object entity) {
    intercept(
        statementOf(statementId(mapperInterface, MybatisCommand.INSERT), SqlCommandType.INSERT),
        entity);
  }

  /**
   * 주어진 인터셉터에 INSERT 문장 하나를 태우고, 감사 컬럼이 하나라도 채워졌는지 돌려준다.
   *
   * <p>{@code MybatisAuditAutoConfigurationTest} 가 "감사자 빈이 없으면 아무 일도 하지 않는다" 를 확인하는 데 쓴다. 픽스처와 배선이
   * 여기 다 있어 그쪽에서 다시 만들 이유가 없다.
   */
  static boolean stampsAnything(final MybatisAuditColumnInterceptor target) {
    final AuditEntity entity = new AuditEntity();
    intercept(
        target,
        statementOf(statementId(AuditRepo.class, MybatisCommand.INSERT), SqlCommandType.INSERT),
        entity);
    return entity.createdAt != null
        || entity.createdBy != null
        || entity.updatedAt != null
        || entity.updatedBy != null;
  }

  private void insertBatch(final Class<?> mapperInterface, final List<?> entities) {
    intercept(
        statementOf(
            statementId(mapperInterface, MybatisCommand.INSERT_BATCH), SqlCommandType.INSERT),
        // MyBatis 는 컬렉션 인자를 ParamMap{collection=..., list=...} 으로 감싸서 Executor 에 넘긴다.
        ParamNameResolver.wrapToMapIfCollection(entities, null));
  }

  private static Method mapperMethod(final Class<?> mapperInterface, final String methodName) {
    for (Method method : mapperInterface.getMethods()) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    throw new AssertionError("no such mapper method: " + methodName);
  }

  /** MyBatis 가 만드는 {@code ParamMap} 을 그대로 만들어 인터셉터를 태운 뒤, 프로바이더까지 돌려 완성된 UPDATE 문장을 돌려준다. */
  private String updateSql(
      final Class<?> mapperInterface,
      final Map<String, Object> updateMap,
      final Map<String, Object> whereConditions) {
    final Configuration configuration = new Configuration();
    configuration.addMapper(mapperInterface);
    final MappedStatement statement =
        configuration.getMappedStatement(
            statementId(mapperInterface, MybatisCommand.UPDATE_MAP_BY_MAP), false);
    final Object parameter =
        new ParamNameResolver(
                configuration, mapperMethod(mapperInterface, MybatisCommand.UPDATE_MAP_BY_MAP))
            .getNamedParams(new Object[] {null, updateMap, whereConditions});
    intercept(statement, parameter);
    return statement.getBoundSql(parameter).getSql().replace('\n', ' ');
  }

  private static Map<String, Object> mapOf(final Object... keyValues) {
    final Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  // ===========================================
  // INSERT
  // ===========================================

  @Test
  @DisplayName("INSERT 는 생성/수정 일시와 생성/수정자 네 필드를 모두 채워야 한다")
  void insert_ShouldStampAllFourAuditColumns() {
    // given
    final AuditEntity entity = new AuditEntity();
    final Instant before = Instant.now();

    // when
    insert(AuditRepo.class, entity);

    // then
    assertThat(entity.createdAt).isNotNull().isBetween(before, Instant.now());
    assertThat(entity.updatedAt).isEqualTo(entity.createdAt);
    assertThat(entity.createdBy).isEqualTo("tester");
    assertThat(entity.updatedBy).isEqualTo("tester");
  }

  @Test
  @DisplayName("호출부가 감사 컬럼을 미리 채워 두었어도 INSERT 는 자기 값으로 덮어써야 한다")
  void insert_ShouldOverwriteCallerSuppliedValues() {
    // given
    final AuditEntity entity = new AuditEntity();
    entity.createdAt = Instant.EPOCH;
    entity.createdBy = "forged";

    // when
    insert(AuditRepo.class, entity);

    // then
    assertThat(entity.createdAt).isAfter(Instant.EPOCH);
    assertThat(entity.createdBy).isEqualTo("tester");
  }

  @Test
  @DisplayName("감사 필드가 없는 엔티티는 그냥 통과해야 한다")
  void insert_ShouldSkipEntityWithoutAuditFields() {
    // given
    final PlainEntity entity = new PlainEntity();
    entity.name = "kim";

    // when
    assertThatCode(() -> insert(PlainRepo.class, entity)).doesNotThrowAnyException();

    // then: 값이 바뀌지 않고, 채울 필드가 없으므로 감사자에게 묻지도 않는다
    assertThat(entity.name).isEqualTo("kim");
    assertThat(auditor.calls).isZero();
  }

  @Test
  @DisplayName("insertBatch 는 모든 행을 같은 시각으로 채워야 한다")
  void insertBatch_ShouldStampEveryRow() {
    // given
    final List<AuditEntity> entities =
        new ArrayList<>(Arrays.asList(new AuditEntity(), new AuditEntity(), new AuditEntity()));

    // when
    insertBatch(AuditRepo.class, entities);

    // then
    for (AuditEntity entity : entities) {
      assertThat(entity.createdAt).isNotNull();
      assertThat(entity.updatedAt).isEqualTo(entity.createdAt);
      assertThat(entity.createdBy).isEqualTo("tester");
      assertThat(entity.updatedBy).isEqualTo("tester");
    }
    assertThat(entities.get(0).createdAt).isEqualTo(entities.get(2).createdAt);
    // 문장 하나에 한 번만 묻는다. 1000행이면 1000번 물을 자리다.
    assertThat(auditor.calls).isEqualTo(1);
  }

  @Test
  @DisplayName("LocalDateTime 은 설정된 타임존의 벽시계로, java.util.Date 는 그대로 채워야 한다")
  void insert_ShouldSupportLocalDateTimeAndDate() {
    // given
    properties.setTimezone("Asia/Seoul");
    final MixedTypeEntity entity = new MixedTypeEntity();
    final Instant before = Instant.now();

    // when
    insert(MixedTypeRepo.class, entity);

    // then
    assertThat(entity.createdAt)
        .isBetween(
            LocalDateTime.ofInstant(before, ZoneId.of("Asia/Seoul")).minusSeconds(1),
            LocalDateTime.ofInstant(Instant.now(), ZoneId.of("Asia/Seoul")).plusSeconds(1));
    assertThat(entity.updatedAt)
        .isBetween(Date.from(before.minusSeconds(1)), Date.from(Instant.now().plusSeconds(1)));
  }

  @Test
  @DisplayName("java.sql.Timestamp 필드도 채울 수 있어야 한다")
  void insert_ShouldSupportSqlTimestamp() {
    // given: 레거시 MyBatis 엔티티에 흔한 타입이다. 못 채우면 기능을 켜는 순간 모든 INSERT 가 죽는다.
    final TimestampEntity entity = new TimestampEntity();
    final Instant before = Instant.now();

    // when
    insert(TimestampRepo.class, entity);

    // then
    assertThat(entity.createdAt)
        .isNotNull()
        .isBetween(Timestamp.from(before.minusSeconds(1)), Timestamp.from(Instant.now()));
  }

  @Test
  @DisplayName("java.sql.Date 는 toInstant() 가 동작하지 않으므로 예외로 끊어야 한다")
  void insert_ShouldRejectSqlDate() {
    // given
    final SqlDateEntity entity = new SqlDateEntity();

    // when & then
    assertThatThrownBy(() -> insert(SqlDateRepo.class, entity))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("java.sql.Date");
  }

  @Test
  @DisplayName("타임존 미설정이면 LocalDateTime 은 UTC 벽시계라 Date 계열과 갈린다")
  void insert_ShouldDivergeBetweenLocalDateTimeAndDateWhenTimezoneUnset() {
    // given: 타임존을 지정하지 않은 기본 상태
    final MixedTypeEntity entity = new MixedTypeEntity();

    // when
    insert(MixedTypeRepo.class, entity);

    // then: 같은 순간에서 나왔지만 LocalDateTime 은 UTC 로 읽은 값이다(getZoneId 기본값)
    assertThat(entity.createdAt.truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(LocalDateTime.ofInstant(entity.updatedAt.toInstant(), ZoneOffset.UTC));
    // 그리고 Date 는 SQL 로 찍힐 때 getDateZoneId(미설정이면 JVM 존)를 따르므로, KST JVM 에서는
    // 한 행의 두 감사 컬럼이 9시간 어긋난 값으로 저장된다. 둘 다 쓰는 스키마라면 타임존을 지정해야 한다.
    assertThat(LocalDateTime.ofInstant(entity.updatedAt.toInstant(), ZoneId.of("Asia/Seoul")))
        .isEqualTo(entity.createdAt.truncatedTo(ChronoUnit.MILLIS).plusHours(9));
  }

  @Test
  @DisplayName("지원하지 않는 일시 필드 타입은 조용히 건너뛰지 않고 예외를 던져야 한다")
  void unsupportedTimestampType_ShouldThrow() {
    // given
    final BadTimestampEntity entity = new BadTimestampEntity();

    // when & then
    assertThatThrownBy(() -> insert(BadTimestampRepo.class, entity))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("createdAt");
  }

  @Test
  @DisplayName("지원하지 않는 감사자 필드 타입은 조용히 건너뛰지 않고 예외를 던져야 한다")
  void unsupportedAuditorType_ShouldThrow() {
    // given
    final BadAuditorEntity entity = new BadAuditorEntity();

    // when & then
    assertThatThrownBy(() -> insert(BadAuditorRepo.class, entity))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("createdBy");
  }

  @Test
  @DisplayName("감사자가 비어 있으면 *_BY 는 건드리지 않고 일시만 채워야 한다")
  void emptyAuditor_ShouldLeaveAuditorColumnsUntouched() {
    // given: 배치나 스케줄러처럼 세션이 없는 경로는 정상이다
    final AuditEntity entity = new AuditEntity();

    // when
    intercept(
        new MybatisAuditColumnInterceptor(Optional::empty, properties),
        statementOf(statementId(AuditRepo.class, MybatisCommand.INSERT), SqlCommandType.INSERT),
        entity);

    // then
    assertThat(entity.createdAt).isNotNull();
    assertThat(entity.updatedAt).isNotNull();
    assertThat(entity.createdBy).isNull();
    assertThat(entity.updatedBy).isNull();
  }

  @Test
  @DisplayName("필드명을 바꾸면 그 이름의 필드를 채워야 한다")
  void customFieldNames_ShouldBeHonored() {
    // given
    properties.setCreatedAt("regDt");
    properties.setCreatedBy("regId");
    final CustomNamedEntity entity = new CustomNamedEntity();

    // when
    insert(CustomNamedRepo.class, entity);

    // then
    assertThat(entity.regDt).isNotNull();
    assertThat(entity.regId).isEqualTo("tester");
  }

  // ===========================================
  // UPDATE
  // ===========================================

  @Test
  @DisplayName("UPDATE 는 호출부가 넣은 감사 키를 지우고 수정 일시/수정자를 자기 값으로 넣어야 한다")
  void update_ShouldReplaceCallerSuppliedAuditKeys() {
    // given: 호출부가 감사 컬럼을 위조해서 넘긴다
    final Map<String, Object> updateMap =
        mapOf(
            "name", "kim",
            "createdAt", Instant.EPOCH,
            "createdBy", "forged",
            "updatedBy", "forged");

    // when
    final String sql = updateSql(AuditRepo.class, updateMap, mapOf("id", 1L));

    // then: 생성 계열은 아예 빠지고, 수정 계열은 인터셉터의 값이 들어간다
    assertThat(sql).doesNotContain("created_at").doesNotContain("created_by");
    assertThat(sql).doesNotContain("forged");
    assertThat(sql).contains("`updated_by` = 'tester'");
    assertThat(sql)
        .matches(".*`updated_at` = '\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}'.*");
    assertThat(sql).contains("`name` = 'kim'").contains("WHERE (`id` = 1)");
  }

  @Test
  @DisplayName("호출부가 넘긴 updateMap 은 절대 변형되지 않아야 한다")
  void update_ShouldNotMutateCallerMap() {
    // given: 소비자가 static final 공유 상수를 여러 스레드에서 넘기는 것이 정상적인 사용이다.
    // 거기에 remove/put 을 하면 HashMap 동시 변형이 되고, 이 라이브러리는 인자를 변형한 적이 없다.
    final Map<String, Object> callerMap = mapOf("name", "kim", "memo", null, "createdBy", "forged");
    final Map<String, Object> before = new LinkedHashMap<>(callerMap);

    // when
    final String sql = updateSql(AuditRepo.class, callerMap, mapOf("id", 1L));

    // then: 문장에는 반영되었지만 호출부의 맵은 그대로다
    assertThat(sql).contains("`updated_by` = 'tester'").doesNotContain("forged");
    assertThat(callerMap).isEqualTo(before).containsEntry("createdBy", "forged");
  }

  @Test
  @DisplayName("엔티티에 없는 이름의 감사 키는 지우지 않고 그대로 두어야 한다")
  void update_ShouldOnlyRemoveAuditKeysThatExistOnEntity() {
    // given: PlainEntity 에는 createdBy 필드가 없다. 조용히 지우면 호출부의 잘못된 키가 묻힌다 --
    // 원래는 getColumnName 이 "entity 에 포함되지 않는 필드 발견" 으로 끊어 주던 자리다.
    final Map<String, Object> updateMap = mapOf("name", "kim", "createdBy", "x");

    // when & then: 프로바이더가 던진 예외는 MyBatis 가 BuilderException 으로 감싼다
    assertThatThrownBy(() -> updateSql(PlainRepo.class, updateMap, mapOf("id", 1L)))
        .hasRootCauseInstanceOf(MybatisRepositoryException.class)
        .hasStackTraceContaining("entity 에 포함되지 않는 필드 발견 : createdBy");
  }

  @Test
  @DisplayName("생성 계열만 있는 엔티티에서 감사 키만 넘어오면 원인을 알 수 있는 예외를 던져야 한다")
  void update_ShouldExplainWhenNothingIsLeftAfterRemovingAuditKeys() {
    // given: 생성 계열은 UPDATE 에서 다시 쓰지 않으므로 남는 컬럼이 없다.
    // 라이브러리의 "'updateMap' is required" 메시지로는 왜 비었는지 알 수 없다.
    final Map<String, Object> updateMap = mapOf("createdBy", "x");

    // when & then
    assertThatThrownBy(() -> updateSql(CreatedOnlyRepo.class, updateMap, mapOf("id", 1L)))
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("감사 컬럼");
  }

  @Test
  @DisplayName("빈 updateMap 은 감사 스탬프만으로 되살아나 수정 일시/수정자만 갱신하는 UPDATE 가 돼야 한다")
  void update_ShouldReviveEmptyUpdateMapWithStampsOnly() {
    // given: 엔티티 경로에서 모든 필드가 null 이면 toNonNullMap 이 빈 맵을 넘긴다. 라이브러리의 빈 updateMap
    // 가드는 프로바이더 안에 있고 이 인터셉터가 그보다 먼저 돌므로, 감사 기능이 켜져 있으면 빈 맵에 스탬프가
    // 들어가 그 가드에 닿지 않는다. 지금 동작이 그렇다는 사실을 고정한다 -- 처음부터 빈 맵을 거절할지는
    // 별도 결정이고, 위 테스트가 다루는 "걷어내서 비었다" 와는 다른 경우다.
    final Map<String, Object> updateMap = mapOf();

    // when
    final String sql = updateSql(AuditRepo.class, updateMap, mapOf("id", 1L));

    // then: SET 에는 수정 계열 두 컬럼만 있고 예외는 없다
    assertThat(sql).contains("`updated_by` = 'tester'").contains("`updated_at` = '");
    assertThat(sql).doesNotContain("`name`").doesNotContain("`memo`").doesNotContain("created_");
    assertThat(sql).contains("WHERE (`id` = 1)");
  }

  @Test
  @DisplayName("UPDATE 에서 값이 null 인 키는 그대로 살아남아 SET col = null 로 나가야 한다")
  void update_ShouldKeepNullValuedKeys() {
    // given: 키가 있고 값이 null 이면 "그 컬럼을 NULL 로" 라는 뜻이다. 걸러 내면 컬럼을 비울 방법이 사라진다.
    final Map<String, Object> updateMap = mapOf("name", "kim", "memo", null);

    // when
    final String sql = updateSql(AuditRepo.class, updateMap, mapOf("id", 1L));

    // then
    assertThat(sql).contains("`memo` = null");
  }

  @Test
  @DisplayName("UPDATE 의 SET 절 순서는 호출부가 준 맵의 순서를 그대로 유지해야 한다")
  void update_ShouldKeepSetClauseOrder() {
    // given
    final Map<String, Object> updateMap = mapOf("name", "kim", "memo", null);

    // when
    final String sql = updateSql(AuditRepo.class, updateMap, mapOf("id", 1L));

    // then: 준 순서 뒤에 감사 컬럼이 붙는다
    assertThat(sql)
        .containsSubsequence(
            "SET `name` = 'kim'", ", `memo` = null", ", `updated_at` = '", ", `updated_by` = ");
  }

  @Test
  @DisplayName("불변 맵으로 들어와도 순서를 유지한 새 맵으로 바꿔 넣어야 한다")
  void update_ShouldReplaceImmutableMapKeepingOrder() {
    // given
    final Map<String, Object> updateMap =
        Collections.unmodifiableMap(mapOf("name", "kim", "memo", null));

    // when
    final String sql = updateSql(AuditRepo.class, updateMap, mapOf("id", 1L));

    // then
    assertThat(sql)
        .containsSubsequence(
            "SET `name` = 'kim'", ", `memo` = null", ", `updated_at` = '", ", `updated_by` = ");
  }

  @Test
  @DisplayName("updateMap 을 가리키는 ParamMap 별칭 키 세 개에 모두 되써야 한다")
  void update_ShouldWriteBackToEveryAliasKey() {
    // given: -parameters 로 빌드된 jar 는 updateMap/param2 로, 그렇지 않은 빌드는 arg1 로 읽는다.
    // 하나만 되쓰면 다른 빌드에서 감사 컬럼이 조용히 빈다.
    final Map<String, Object> updateMap = Collections.unmodifiableMap(mapOf("name", "kim"));
    final Map<String, Object> whereConditions = mapOf("id", 1L);
    final MapperMethod.ParamMap<Object> paramMap = new MapperMethod.ParamMap<>();
    paramMap.put("context", null);
    paramMap.put("param1", null);
    paramMap.put("updateMap", updateMap);
    paramMap.put("param2", updateMap);
    paramMap.put("arg1", updateMap);
    paramMap.put("whereConditions", whereConditions);
    paramMap.put("param3", whereConditions);

    // when
    intercept(
        statementOf(
            statementId(AuditRepo.class, MybatisCommand.UPDATE_MAP_BY_MAP), SqlCommandType.UPDATE),
        paramMap);

    // then
    for (String key : Arrays.asList("updateMap", "param2", "arg1")) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> stamped = (Map<String, Object>) paramMap.get(key);
      assertThat(stamped).containsKey("updatedAt");
      assertThat(stamped).containsEntry("updatedBy", "tester");
      assertThat(stamped).containsEntry("name", "kim");
    }
    // where 슬롯은 건드리지 않는다
    assertThat(paramMap.get("whereConditions")).isSameAs(whereConditions);
    assertThat(paramMap.get("param3")).isSameAs(whereConditions);
  }

  @Test
  @DisplayName("MybatisRepositoryBase 하위가 아닌 매퍼의 문장은 건드리지 않아야 한다")
  void nonRepositoryMapper_ShouldBeLeftAlone() {
    // given: 메서드 이름까지 같지만 저장소 인터페이스가 아니다
    final Map<String, Object> updateMap = mapOf("name", "kim", "updatedBy", "forged");
    final MapperMethod.ParamMap<Object> paramMap = new MapperMethod.ParamMap<>();
    paramMap.put("updateMap", updateMap);
    paramMap.put("param1", updateMap);

    // when
    intercept(
        statementOf(
            statementId(NotARepositoryMapper.class, MybatisCommand.UPDATE_MAP_BY_MAP),
            SqlCommandType.UPDATE),
        paramMap);

    // then
    assertThat(updateMap).containsEntry("updatedBy", "forged").doesNotContainKey("updatedAt");
    assertThat(auditor.calls).isZero();
  }

  @Test
  @DisplayName("매퍼 클래스를 찾을 수 없는 문장(XML 매퍼 등)은 조용히 넘어가야 한다")
  void unknownMapperClass_ShouldBeSkippedSilently() {
    // given
    final Map<String, Object> updateMap = mapOf("name", "kim");
    final MapperMethod.ParamMap<Object> paramMap = new MapperMethod.ParamMap<>();
    paramMap.put("updateMap", updateMap);

    // when & then
    assertThatCode(
            () ->
                intercept(
                    statementOf(
                        "com.example.NoSuchMapper." + MybatisCommand.UPDATE_MAP_BY_MAP,
                        SqlCommandType.UPDATE),
                    paramMap))
        .doesNotThrowAnyException();
    assertThat(updateMap).doesNotContainKey("updatedAt");
  }

  @Test
  @DisplayName("소비자가 저장소 인터페이스에 직접 붙인 다른 이름의 문장은 건드리지 않아야 한다")
  void otherStatementOnRepository_ShouldBeLeftAlone() {
    // given: 같은 매퍼라도 이 라이브러리가 만드는 쓰기 프로바이더가 아니면 대상이 아니다
    final Map<String, Object> updateMap = mapOf("name", "kim");
    final MapperMethod.ParamMap<Object> paramMap = new MapperMethod.ParamMap<>();
    paramMap.put("updateMap", updateMap);
    paramMap.put("param2", updateMap);

    // when
    intercept(
        statementOf(statementId(AuditRepo.class, "touchSomething"), SqlCommandType.UPDATE),
        paramMap);

    // then
    assertThat(updateMap).doesNotContainKey("updatedAt");
  }
}
