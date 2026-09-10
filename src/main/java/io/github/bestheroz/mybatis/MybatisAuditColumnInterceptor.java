package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 감사 컬럼(생성/수정 일시, 생성/수정자)을 쓰기 문장이 만들어지기 직전에 채운다.
 *
 * <p>선택 기능이다. {@link MybatisAuditorAware} 빈이 없으면 {@code MybatisAutoConfiguration} 이 감사자 없는 인터셉터를
 * 만들고, 그때 이 클래스는 {@link #intercept} 첫 줄에서 곧바로 빠져나간다. 즉 켜지 않으면 statement 판정조차 하지 않고 라이브러리 동작은 예전
 * 그대로다. 조건부로 빈을 만들지 않는 대신 언제나 만들어 두는 이유는 {@code MybatisAutoConfiguration} 쪽에 적어 두었다.
 *
 * <h2>무엇을 채우는가</h2>
 *
 * <ul>
 *   <li><b>INSERT</b> -- 엔티티의 {@code createdAt}/{@code updatedAt} 에 현재 시각을, {@code
 *       createdBy}/{@code updatedBy} 에 감사자를 리플렉션으로 대입한다. {@code insertBatch} 는 모든 행을 같은 시각으로 채운다.
 *   <li><b>UPDATE</b> -- {@code updateMap} 을 <b>복사한 뒤</b> 그 복사본에서 감사 키를 제거하고 {@code
 *       updatedAt}/{@code updatedBy} 를 인터셉터가 계산한 값으로 넣는다. 호출부가 감사 컬럼을 위조하지 못하게 하는 것이 목적이다. 생성 계열은
 *       UPDATE 에서 다시 쓰지 않는다. <b>호출부가 넘긴 맵은 절대 변형하지 않는다</b> -- 그 맵은 소비자 소유이고 공유 상수일 수 있다.
 * </ul>
 *
 * <p>필드 이름은 {@link MybatisRepositoryProperties} 에서 읽으므로 소비자가 바꿀 수 있다. 그 이름의 {@code @Column} 필드가
 * 엔티티에 없으면 그냥 건너뛴다 -- 감사 컬럼이 없는 엔티티가 정상이기 때문이다. 반대로 필드는 있는데 타입이 지원 범위 밖이면 {@link
 * MybatisRepositoryException} 으로 끊는다. 감사 컬럼이 조용히 비는 것이 이 기능의 유일한 치명적 실패 모양이라, 애매한 자리는 전부 시끄럽게 만들어
 * 둔다.
 *
 * <h2>대상 범위</h2>
 *
 * <p>statement id({@code <매퍼FQN>.<메서드명>})가 {@link MybatisRepositoryBase} 의 하위 인터페이스에 속하고, 메서드 이름이 이
 * 라이브러리가 만드는 세 쓰기 프로바이더({@code buildInsertSQL}/{@code buildInsertBatchSQL}/{@code buildUpdateSQL})
 * 중 하나일 때만 손댄다. XML 매퍼, 소비자 자작 매퍼, 그리고 같은 저장소 인터페이스에 소비자가 직접 붙인 {@code @Update} 메서드는 건드리지 않는다. 매퍼
 * 클래스를 못 찾으면(XML 매퍼처럼 statement id 가 인터페이스가 아닌 경우) 조용히 넘어간다.
 *
 * <h2>UPDATE 파라미터의 함정</h2>
 *
 * <p>MyBatis 는 프로바이더에 인자를 그대로 넘기지 않고 {@code ParamMap} 을 넘긴다. {@code buildUpdateSQL(ProviderContext,
 * Map updateMap, Map whereConditions)} 의 updateMap 슬롯을 가리키는 키는 세 개다 -- 이름 {@code updateMap}, 인덱스
 * {@code param2}(ProviderContext 가 첫 슬롯이라 두 번째다), 그리고 {@code -parameters} 없이 컴파일된 빌드의 {@code arg1}.
 * 프로바이더가 어느 키로 읽을지는 jar 의 {@code MethodParameters} 속성에 달려 있으므로 <b>셋 다 읽고 셋 다 되쓴다.</b> 하나만 되쓰면 다른
 * 빌드에서 감사 컬럼이 조용히 빈다.
 *
 * <p>{@code ParamMap#get} 은 없는 키에 예외를 던지므로 언제나 {@code containsKey} 로 먼저 확인한다.
 *
 * <h2>null 을 거르지 않는다</h2>
 *
 * <p>{@code updateMap} 에 값이 {@code null} 인 키가 있으면 그대로 둔다. 이 라이브러리에서 "키가 있고 값이 null" 은 {@code `컬럼` =
 * null} 을 쓰라는 뜻이고(CLAUDE.md 의 UPDATE SET protocol), 그것을 걸러내면 컬럼을 비우는 방법이 사라진다. 여기에 null 필터를 넣지 말 것.
 */
@Intercepts(
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}))
public class MybatisAuditColumnInterceptor implements Interceptor {
  private static final Logger log = LoggerFactory.getLogger(MybatisAuditColumnInterceptor.class);

  /**
   * updateMap 슬롯을 가리키는 {@code ParamMap} 키 후보. 클래스 주석의 "UPDATE 파라미터의 함정" 참고. where 슬롯은 {@code
   * arg2}/{@code param3} 이라 겹치지 않는다.
   */
  private static final List<String> UPDATE_MAP_PARAM_KEYS =
      Collections.unmodifiableList(Arrays.asList("updateMap", "param2", "arg1"));

  /** 이 라이브러리가 만드는 쓰기 프로바이더의 메서드 이름. 소비자가 같은 인터페이스에 직접 붙인 문장은 대상이 아니다. */
  private static final Set<String> AUDITED_STATEMENT_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  MybatisCommand.INSERT,
                  MybatisCommand.INSERT_BATCH,
                  MybatisCommand.UPDATE_MAP_BY_MAP)));

  private final MybatisAuditorAware auditorAware;
  private final MybatisRepositoryProperties properties;
  private final MybatisEntityHelper entityHelper = new MybatisEntityHelper();

  /**
   * statement id → 감사 대상이면 엔티티 클래스, 아니면 비어 있음.
   *
   * <p>"대상이 아님" 도 담는다. 엔티티 메타데이터와 마찬가지로 JVM 이 사는 동안 바뀌지 않는 사실이고, 담아 두지 않으면 XML 매퍼 문장 하나하나가 매번
   * {@code Class.forName} 을 지나게 된다. 캐시하지 말아야 할 것은 예외로 끝나는 조회이지(CLAUDE.md), 정상적인 "아니오" 가 아니다.
   */
  private final Map<String, Optional<Class<?>>> statementEntityCache = new ConcurrentHashMap<>();

  /** 엔티티 → (자바 필드명 → {@code @Column} 필드). 없는 이름은 비어 있는 값으로 담는다. */
  private final Map<Class<?>, Map<String, Optional<Field>>> fieldCache = new ConcurrentHashMap<>();

  /**
   * @param auditorAware 감사자 제공자. {@code null} 이면 이 인터셉터는 아무 일도 하지 않는다 -- 자동설정이 {@link
   *     MybatisAuditorAware} 빈을 찾지 못했을 때 그렇게 만든다.
   */
  public MybatisAuditColumnInterceptor(final MybatisAuditorAware auditorAware) {
    this(auditorAware, MybatisRepositoryProperties.getInstance());
  }

  public MybatisAuditColumnInterceptor(
      final MybatisAuditorAware auditorAware, final MybatisRepositoryProperties properties) {
    this.auditorAware = auditorAware;
    this.properties = properties != null ? properties : MybatisRepositoryProperties.getInstance();
  }

  @Override
  public Object intercept(final Invocation invocation) throws Throwable {
    if (auditorAware == null) {
      // 감사자 제공자가 없으면 기능 전체가 꺼진 상태다. statement 판정조차 하지 않는다.
      return invocation.proceed();
    }
    final Object[] args = invocation.getArgs();
    final MappedStatement mappedStatement = (MappedStatement) args[0];
    final SqlCommandType commandType = mappedStatement.getSqlCommandType();
    if (commandType == SqlCommandType.INSERT || commandType == SqlCommandType.UPDATE) {
      final Class<?> entityClass = resolveAuditTarget(mappedStatement);
      if (entityClass != null) {
        if (commandType == SqlCommandType.INSERT) {
          stampInsert(args[1]);
        } else {
          stampUpdate(entityClass, args[1]);
        }
      }
    }
    return invocation.proceed();
  }

  /**
   * {@code Interceptor#plugin} 은 MyBatis 3.5.1 부터 default 메소드지만, 이 라이브러리의 하한은 그보다 아래다. 직접 구현해 두지
   * 않으면 옛 버전에서 {@code AbstractMethodError} 가 난다.
   */
  @Override
  public Object plugin(final Object target) {
    return Plugin.wrap(target, this);
  }

  /** {@link #plugin(Object)} 과 같은 이유로 직접 구현해 둔다. 이 인터셉터는 설정 프로퍼티를 받지 않는다. */
  @Override
  public void setProperties(final Properties properties) {
    // 받을 것이 없다.
  }

  // ===========================================
  // 대상 판정
  // ===========================================

  /** 감사 대상이면 엔티티 클래스를, 아니면 {@code null} 을 돌려준다. */
  private Class<?> resolveAuditTarget(final MappedStatement mappedStatement) {
    final String statementId = mappedStatement.getId();
    // 적중 경로는 plain get 으로 먼저 본다(CLAUDE.md 의 computeIfAbsent 규칙).
    Optional<Class<?>> cached = statementEntityCache.get(statementId);
    if (cached == null) {
      cached = statementEntityCache.computeIfAbsent(statementId, this::resolveEntityClass);
    }
    return cached.orElse(null);
  }

  private Optional<Class<?>> resolveEntityClass(final String statementId) {
    final int lastDot = statementId.lastIndexOf('.');
    if (lastDot < 0) {
      return Optional.empty();
    }
    if (!AUDITED_STATEMENT_METHODS.contains(statementId.substring(lastDot + 1))) {
      return Optional.empty();
    }
    final String mapperName = statementId.substring(0, lastDot);
    final Class<?> mapperInterface;
    try {
      mapperInterface = Resources.classForName(mapperName);
    } catch (final ClassNotFoundException | LinkageError e) {
      // statement id 가 인터페이스 이름이 아닐 수 있다(XML 매퍼 등). 우리 대상이 아니라는 뜻이므로 조용히 넘긴다.
      log.debug("감사 대상 판정을 건너뛴다(매퍼 클래스를 찾지 못함): {}", mapperName);
      return Optional.empty();
    }
    if (!MybatisRepositoryBase.class.isAssignableFrom(mapperInterface)) {
      return Optional.empty();
    }
    final Class<?> entityClass = entityHelper.extractEntityClassFromMapper(mapperInterface);
    if (entityClass == null) {
      // 타입 인자 없이 확장한 매퍼. 라이브러리의 build 메소드가 곧 "cannot determine entity class" 로 끊는다.
      return Optional.empty();
    }
    return Optional.<Class<?>>of(entityClass);
  }

  // ===========================================
  // INSERT
  // ===========================================

  private void stampInsert(final Object parameter) {
    final Instant now = Instant.now();
    final Auditor auditor = new Auditor();
    for (Object entity : toEntities(parameter)) {
      if (entity == null) {
        // 곧 buildInsertSQL 이 예외로 끊는다. 여기서 먼저 NPE 를 내지는 않는다.
        continue;
      }
      final Class<?> entityClass = entity.getClass();
      setTimestamp(entity, entityClass, properties.getCreatedAt(), now);
      setTimestamp(entity, entityClass, properties.getUpdatedAt(), now);
      setAuditor(entity, entityClass, properties.getCreatedBy(), auditor);
      setAuditor(entity, entityClass, properties.getUpdatedBy(), auditor);
    }
  }

  /** {@code insertBatch} 는 {@code ParamMap{list=..., collection=...}} 으로 감싸여 들어온다. */
  private static Collection<?> toEntities(final Object parameter) {
    if (parameter == null) {
      return Collections.emptyList();
    }
    if (parameter instanceof Collection) {
      return (Collection<?>) parameter;
    }
    if (parameter instanceof Map) {
      for (Object value : ((Map<?, ?>) parameter).values()) {
        if (value instanceof Collection) {
          return (Collection<?>) value;
        }
      }
      return Collections.emptyList();
    }
    return Collections.singletonList(parameter);
  }

  private void setTimestamp(
      final Object entity, final Class<?> entityClass, final String fieldName, final Instant now) {
    final Field field = findField(entityClass, fieldName);
    if (field == null) {
      return;
    }
    setFieldValue(entity, field, timestampValueOf(field, now));
  }

  private void setAuditor(
      final Object entity,
      final Class<?> entityClass,
      final String fieldName,
      final Auditor auditor) {
    final Field field = findField(entityClass, fieldName);
    if (field == null) {
      return;
    }
    // 타입 검사는 감사자가 있든 없든 한다. 세션이 있는 요청에서만 터지면 설정 오류를 훨씬 늦게 알게 된다.
    requireStringField(field);
    final String value = auditor.get();
    if (value != null) {
      setFieldValue(entity, field, value);
    }
  }

  // ===========================================
  // UPDATE
  // ===========================================

  private void stampUpdate(final Class<?> entityClass, final Object parameter) {
    if (!(parameter instanceof Map)) {
      return;
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> paramMap = (Map<String, Object>) parameter;
    final Map<String, Object> updateMap = findUpdateMap(paramMap);
    if (updateMap == null) {
      return;
    }

    // 넣을 값을 먼저 만든다. 지원하지 않는 타입이면 맵을 건드리기 전에 여기서 끊긴다.
    final Map<String, Object> stamps = new LinkedHashMap<>();
    final Field updatedAtField = findField(entityClass, properties.getUpdatedAt());
    if (updatedAtField != null) {
      stamps.put(properties.getUpdatedAt(), timestampValueOf(updatedAtField, Instant.now()));
    }
    final Field updatedByField = findField(entityClass, properties.getUpdatedBy());
    if (updatedByField != null) {
      requireStringField(updatedByField);
      final String auditor = new Auditor().get();
      if (auditor != null) {
        stamps.put(properties.getUpdatedBy(), auditor);
      }
    }

    // 호출부의 맵은 절대 제자리에서 고치지 않고 언제나 복사한다.
    //
    // updateMapByMap/updateMapById 에 넘어온 맵은 소비자 소유다. static final 로 둔 공유 상수를 여러
    // 스레드가 함께 넘기는 것은 정상적인 사용인데, 거기에 remove/put 을 하면 HashMap 을 동시에 변형해
    // 무한 루프나 엔트리 유실로 이어진다. 게다가 이 라이브러리는 지금까지 인자를 변형한 적이 없다.
    // 복사에는 잃을 것도 없다 -- LinkedHashMap 은 원본의 순회 순서를 그대로 물려받으므로 SET 절 순서가
    // 유지되고, updateMap 과 whereConditions 가 같은 인스턴스로 넘어와도 WHERE 가 오염되지 않는다.
    //
    // 값이 null 인 키는 복사본에서도 건드리지 않는다. 클래스 주석의 "null 을 거르지 않는다" 참고.
    final Map<String, Object> stamped = new LinkedHashMap<>(updateMap);
    final List<String> auditKeys = auditKeysOn(entityClass);
    for (String auditKey : auditKeys) {
      stamped.remove(auditKey);
    }
    stamped.putAll(stamps);
    if (stamped.isEmpty()) {
      // 라이브러리의 빈 updateMap 가드가 "'updateMap' is required" 로 끊어 주기는 하지만, 호출부가
      // 키를 넣어 보냈는데 우리가 걷어낸 경우라 그 메시지로는 원인을 찾을 수 없다.
      throw new MybatisRepositoryException(
          "감사 컬럼을 걷어내고 나니 UPDATE 할 컬럼이 남지 않았다 : "
              + auditKeys
              + " -- 생성 계열 감사 컬럼은 UPDATE 에서 다시 쓰지 않는다");
    }
    writeBackUpdateMap(paramMap, updateMap, stamped);
  }

  /**
   * 엔티티에 실제로 있는 감사 필드 이름만 돌려준다.
   *
   * <p>없는 이름까지 지우면 호출부의 오타나 잘못 넣은 키가 조용히 사라진다. 그런 키는 원래 {@code getColumnName} 이 "entity 에 포함되지 않는
   * 필드 발견" 으로 끊어 주던 자리다.
   */
  private List<String> auditKeysOn(final Class<?> entityClass) {
    final List<String> auditKeys = new ArrayList<>(4);
    for (String fieldName :
        Arrays.asList(
            properties.getCreatedAt(),
            properties.getCreatedBy(),
            properties.getUpdatedAt(),
            properties.getUpdatedBy())) {
      if (findField(entityClass, fieldName) != null) {
        auditKeys.add(fieldName);
      }
    }
    return auditKeys;
  }

  /** updateMap 슬롯을 가리키는 세 키를 차례로 본다. {@code ParamMap#get} 이 던지므로 {@code containsKey} 가 먼저다. */
  private static Map<String, Object> findUpdateMap(final Map<String, Object> paramMap) {
    for (String key : UPDATE_MAP_PARAM_KEYS) {
      if (paramMap.containsKey(key)) {
        final Object value = paramMap.get(key);
        if (value instanceof Map) {
          @SuppressWarnings("unchecked")
          final Map<String, Object> updateMap = (Map<String, Object>) value;
          return updateMap;
        }
      }
    }
    return null;
  }

  /**
   * 세 별칭 키가 모두 같은 인스턴스를 가리키므로, 그 인스턴스를 가리키는 키만 골라 바꾼다 -- {@code whereConditions} 가 우연히 같은 자리에 들어와
   * 있어도 덮지 않기 위해서다.
   */
  private static void writeBackUpdateMap(
      final Map<String, Object> paramMap,
      final Map<String, Object> original,
      final Map<String, Object> stamped) {
    for (String key : UPDATE_MAP_PARAM_KEYS) {
      if (paramMap.containsKey(key) && paramMap.get(key) == original) {
        paramMap.put(key, stamped);
      }
    }
  }

  // ===========================================
  // 필드 조회와 값 변환
  // ===========================================

  /**
   * {@code @Column} 이 붙은 필드만 본다. 매핑되지 않는 필드에 값을 넣어 봐야 SQL 에는 나가지 않으므로, 그런 필드는 "없는 것" 과 같다.
   *
   * <p>같은 이름이 상위 클래스에도 있으면 뒤에 오는 것(상위 클래스 쪽)이 이긴다. {@code
   * MybatisEntityHelper#getEntityFieldsInOrder} 가 값을 꺼낼 때 쓰는 규칙과 같아야, 우리가 채운 필드와 SQL 이 읽는 필드가 어긋나지
   * 않는다.
   */
  private Field findField(final Class<?> entityClass, final String fieldName) {
    Map<String, Optional<Field>> byFieldName = fieldCache.get(entityClass);
    if (byFieldName == null) {
      byFieldName = fieldCache.computeIfAbsent(entityClass, clazz -> new ConcurrentHashMap<>());
    }
    Optional<Field> cached = byFieldName.get(fieldName);
    if (cached == null) {
      Field found = null;
      for (Field field : MybatisEntityHelper.getAllNonExcludedFields(entityClass)) {
        if (field.getName().equals(fieldName)) {
          found = field;
        }
      }
      cached = Optional.ofNullable(found);
      byFieldName.put(fieldName, cached);
    }
    return cached.orElse(null);
  }

  /**
   * 타임스탬프 필드에 넣을 값. 지원 타입은 넷뿐이고 그 밖은 던진다.
   *
   * <p>{@code LocalDateTime} 은 벽시계라 어느 존으로 읽을지 우리가 정해야 한다. {@link
   * MybatisRepositoryProperties#getZoneId()} 를 쓴다 -- 그것이 {@code mybatis-repository.timezone} 이 뜻하는
   * "이 라이브러리가 SQL 에 찍는 시각의 기준 벽시계" 이고, 설정하면 {@code Instant} 경로와 같은 값이 저장된다. {@code getDateZoneId()}
   * 쪽 기본값(JVM 존)은 이미 저장된 값을 옮기지 않으려는 하위호환용이라, 새로 만드는 이 경로에는 해당하지 않는다.
   *
   * <p><b>타임존을 설정하지 않으면 두 타입이 갈린다.</b> {@code LocalDateTime} 은 UTC 벽시계로 채워지는데 {@code
   * java.util.Date}/{@code java.sql.Timestamp} 는 렌더링 때 JVM 기본 존을 따르므로, KST JVM 에서 한 행의 두 감사 컬럼이 9시간
   * 어긋난 값으로 저장된다. 둘 다 쓰는 스키마라면 {@code mybatis-repository.timezone} 을 반드시 지정해야 한다.
   *
   * <p>{@code java.util.Date} 와 {@code java.sql.Timestamp} 는 절대 시각이라 변환이 필요 없다. SQL 로 찍힐 때 {@code
   * getDateZoneId()} 가 적용되는 것은 기존 렌더링 규칙 그대로다. {@code OffsetDateTime}/{@code ZonedDateTime}/{@code
   * LocalDate} 는 렌더러가 지원하지만 감사 컬럼으로는 아직 받지 않는다(후속 확장 후보).
   */
  private Object timestampValueOf(final Field field, final Instant now) {
    final Class<?> type = field.getType();
    if (type == Instant.class) {
      return now;
    }
    if (type == LocalDateTime.class) {
      return LocalDateTime.ofInstant(now, properties.getZoneId());
    }
    if (type == Date.class) {
      return Date.from(now);
    }
    if (type == Timestamp.class) {
      // java.sql.Timestamp 는 진짜 시각이라 toInstant() 가 동작하고, 렌더링도 java.util.Date 분기를 탄다.
      return Timestamp.from(now);
    }
    if (type == java.sql.Date.class || type == java.sql.Time.class) {
      // 이 둘은 java.util.Date 의 하위지만 toInstant() 가 UnsupportedOperationException 을 던진다.
      // 값을 넣어 봐야 SQL 로 찍힐 때 터지므로 여기서 먼저 끊는다.
      throw new MybatisRepositoryException(
          "지원하지 않는 감사 일시 필드 타입 : "
              + describe(field)
              + " -- java.sql.Date/java.sql.Time 은 toInstant() 가 동작하지 않는다."
              + " java.sql.Timestamp 나 Instant 를 쓸 것");
    }
    throw new MybatisRepositoryException(
        "지원하지 않는 감사 일시 필드 타입 : "
            + describe(field)
            + " -- Instant, LocalDateTime, java.util.Date, java.sql.Timestamp 만 채울 수 있다");
  }

  private static void requireStringField(final Field field) {
    if (field.getType() != String.class) {
      throw new MybatisRepositoryException(
          "지원하지 않는 감사자 필드 타입 : " + describe(field) + " -- String 만 채울 수 있다");
    }
  }

  private static String describe(final Field field) {
    return field.getDeclaringClass().getName()
        + "#"
        + field.getName()
        + "("
        + field.getType().getName()
        + ")";
  }

  /**
   * 값을 넣지 못하면 던진다. 감사 컬럼이 비는 것은 조용한 실패이고, 이 기능에서 가장 나쁜 결과다.
   *
   * <p>{@code setAccessible} 은 {@code MybatisEntityHelper} 가 필드를 캐시에 담을 때 이미 끝냈다.
   */
  private static void setFieldValue(final Object entity, final Field field, final Object value) {
    try {
      field.set(entity, value);
    } catch (final IllegalAccessException | RuntimeException e) {
      throw new MybatisRepositoryException("감사 컬럼을 채우지 못했다 : " + describe(field), e);
    }
  }

  /** 감사자는 문장 하나에 한 번만 묻는다. 채울 {@code *_BY} 필드가 없으면 아예 묻지 않는다. */
  private final class Auditor {
    private boolean resolved;
    private String value;

    private String get() {
      if (!resolved) {
        resolved = true;
        final Optional<String> current = auditorAware.getCurrentAuditor();
        value = current != null && current.isPresent() ? current.get() : null;
      }
      return value;
    }
  }
}
