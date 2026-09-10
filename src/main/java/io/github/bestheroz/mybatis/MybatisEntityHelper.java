package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 엔티티(VO/DTO) 클래스에서 오직 @Column 어노테이션이 붙은 필드만 추출합니다. */
public class MybatisEntityHelper {
  private static final Logger log = LoggerFactory.getLogger(MybatisEntityHelper.class);
  private final MybatisStringHelper stringHelper;

  /**
   * 소비자가 {@link MybatisStringHelper} 를 직접 만들지 않아도 되게 하는 편의 생성자. 이 클래스만 필요한 쪽(예: 매퍼에서 엔티티 타입을 뽑는
   * {@link #extractEntityClassFromMapper}) 이 SQL 이스케이프 헬퍼까지 알아야 할 이유는 없다.
   */
  public MybatisEntityHelper() {
    this(new MybatisStringHelper());
  }

  public MybatisEntityHelper(MybatisStringHelper stringHelper) {
    this.stringHelper = stringHelper;
  }

  /**
   * 특정 클래스에 매핑된 테이블 이름을 반환. - 엔티티 클래스에 @Table(name="...")이 붙어 있으면 그 값을 사용 - 없으면 클래스 이름을
   * CamelCase→snake_case 로 변환
   */
  protected String getTableName(final Class<?> entityClass) {
    return MybatisCommand.TABLE_NAME_CACHE.computeIfAbsent(
        entityClass,
        clazz -> {
          String tableName = null;

          // @Table 어노테이션이 붙어 있다면 name() 값을 읽어옴
          for (Annotation ann : clazz.getAnnotations()) {
            String annType = ann.annotationType().getName();
            if (annType.equals("jakarta.persistence.Table")
                || annType.equals("javax.persistence.Table")) {
              try {
                Method nameMethod = ann.annotationType().getMethod("name");
                Object value = nameMethod.invoke(ann);
                if (value instanceof String && !((String) value).isEmpty()) {
                  tableName = (String) value;
                  break;
                }
              } catch (Exception e) {
                log.debug("Failed to get table name from @Table annotation: {}", e.getMessage());
              }
            }
          }

          // @Table이 없거나 name()이 비어 있으면 CamelCase→snake_case
          if (tableName == null) {
            tableName = stringHelper.getCamelCaseToSnakeCase(clazz.getSimpleName()).toLowerCase();
          }

          return tableName;
        });
  }

  /**
   * 엔티티 클래스에 붙은 모든 @Column 어노테이션 필드명(자바 필드 이름) 집합을 반환.
   *
   * <p>필드 목록 자체는 이미 캐시되어 있었지만 이름 집합은 부를 때마다 새로 만들고 있었다. 전체 컬럼 SELECT 와 배치 인서트가 질의마다 거치는 자리다.
   */
  protected Set<String> getEntityFields(final Class<?> entityClass) {
    return MybatisCommand.FIELD_NAME_CACHE.computeIfAbsent(
        entityClass,
        clazz ->
            Collections.unmodifiableSet(
                getAllNonExcludedFields(clazz).stream()
                    .map(Field::getName)
                    .collect(Collectors.toSet())));
  }

  /**
   * {@link #getEntityFields(Class)} 의 순회 순서에 맞춘 {@link Field} 목록.
   *
   * <p>배치 인서트는 행마다 {@code MybatisCommand.toMap} 으로 {@code HashMap} 을 만든 뒤 컬럼 이름으로 다시 꺼내 쓰고 있었다.
   * 1000행 x 20컬럼이면 그 Map 1000개와 엔트리 2만 개가 통째로 쓰레기가 된다. 값을 꺼내는 순서가 정해져 있으므로 그 순서대로 {@link Field} 만
   * 담아 두면 Map 없이 바로 읽을 수 있다.
   *
   * <p>같은 이름의 필드가 상위 클래스에도 있으면 {@code toMap} 의 {@code put} 과 똑같이 뒤에 오는 것(상위 클래스 쪽)이 이긴다.
   */
  protected List<Field> getEntityFieldsInOrder(final Class<?> entityClass) {
    return MybatisCommand.ORDERED_FIELD_CACHE.computeIfAbsent(
        entityClass,
        clazz -> {
          final Map<String, Field> byName = new HashMap<>();
          for (Field field : getAllNonExcludedFields(clazz)) {
            byName.put(field.getName(), field);
          }
          final Set<String> fieldNames = getEntityFields(clazz);
          final List<Field> ordered = new ArrayList<>(fieldNames.size());
          for (String fieldName : fieldNames) {
            ordered.add(byName.get(fieldName));
          }
          return Collections.unmodifiableList(ordered);
        });
  }

  /**
   * 자바 필드 이름에 대응하는, 백틱으로 감싼 DB 컬럼명을 돌려준다.
   *
   * <p>전체 컬럼 SELECT 는 질의마다 컬럼 수만큼 {@code getColumnName} 조회와 식별자 검증, 문자열 이어붙이기를 다시 했다. 결과는 JVM 이 사는
   * 동안 바뀌지 않으므로 감싼 문자열째로 담아 둔다. 매핑되지 않은 이름과 식별자 규칙을 어긴 이름은 예외로 끝나는 오류 경로라 캐시하지 않는다.
   */
  protected String getWrappedColumnName(final Class<?> entityClass, final String fieldName) {
    if (entityClass == null) {
      return stringHelper.wrapIdentifier(getColumnName(null, fieldName));
    }
    final Map<String, String> byFieldName =
        MybatisCommand.WRAPPED_COLUMN_CACHE.computeIfAbsent(
            entityClass, clazz -> new ConcurrentHashMap<>());
    final String cached = byFieldName.get(fieldName);
    if (cached != null) {
      return cached;
    }
    final String wrapped = stringHelper.wrapIdentifier(getColumnName(entityClass, fieldName));
    byFieldName.put(fieldName, wrapped);
    return wrapped;
  }

  /**
   * 클래스 계층을 순회하며 “실제 필드 레벨”에 @Column 어노테이션이 붙은 것만 필터링해서 리턴.
   *
   * <p>- jakarta.persistence.Column 또는 javax.persistence.Column 둘 다 처리 - 없으면 빈 배열 반환
   */
  protected static List<Field> getAllNonExcludedFields(final Class<?> clazz) {
    return MybatisCommand.FIELD_CACHE.computeIfAbsent(
        clazz,
        entityClass -> {
          List<Field> allFields = new ArrayList<>();
          Class<?> current = entityClass;
          while (current != null && current != Object.class) {
            allFields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
          }

          List<Field> filteredFields =
              allFields.stream()
                  .filter(
                      field -> {
                        for (Annotation ann : field.getAnnotations()) {
                          String annType = ann.annotationType().getName();
                          if (annType.equals("jakarta.persistence.Column")
                              || annType.equals("javax.persistence.Column")) {
                            return true;
                          }
                        }
                        return false;
                      })
                  .distinct()
                  .collect(Collectors.toList());

          // setAccessible 은 여기서 한 번만 해 둔다. 읽을 때마다 다시 부르면서 Field 를 잠글 이유가 없다.
          // 실패하면(SecurityManager 등) 그대로 두고, 값을 읽는 쪽이 예외를 잡아 그 필드만 건너뛴다.
          for (Field field : filteredFields) {
            try {
              field.setAccessible(true);
            } catch (Exception e) {
              log.debug("Failed to make field accessible {}: {}", field.getName(), e.getMessage());
            }
          }

          // 불변 리스트로 만들어 thread safety 보장
          return Collections.unmodifiableList(filteredFields);
        });
  }

  /**
   * 특정 엔티티 클래스와 자바 필드 이름을 받아 DB 컬럼명으로 변환. - 우선순위: @Column(name="...")이 붙어 있으면 name() → - 없으면
   * CamelCase→snake_case 로 변환
   */
  protected String getColumnName(final Class<?> entityClass, final String fieldName) {
    if (fieldName == null) {
      throw new MybatisRepositoryException("fieldName cannot be null");
    }
    if (entityClass == null) {
      return resolveColumnName(null, fieldName);
    }
    final Map<String, String> byFieldName =
        MybatisCommand.COLUMN_NAME_CACHE.computeIfAbsent(
            entityClass, clazz -> new ConcurrentHashMap<>());
    final String cached = byFieldName.get(fieldName);
    if (cached != null) {
      return cached;
    }
    // 못 찾는 이름은 예외로 끝나는 오류 경로라 캐시하지 않는다. 성공한 것만 담는다.
    final String resolved = resolveColumnName(entityClass, fieldName);
    byFieldName.put(fieldName, resolved);
    return resolved;
  }

  private String resolveColumnName(final Class<?> entityClass, final String fieldName) {
    try {
      Field field = findFieldInClassHierarchy(entityClass, fieldName);
      if (field != null) {
        for (Annotation ann : field.getAnnotations()) {
          String annType = ann.annotationType().getName();
          if (annType.equals("jakarta.persistence.Column")
              || annType.equals("javax.persistence.Column")) {
            try {
              Method nameMethod = ann.annotationType().getMethod("name");
              Object value = nameMethod.invoke(ann);
              if (value instanceof String) {
                String nameValue = (String) value;
                if (!nameValue.isEmpty()) {
                  return nameValue;
                }
              }
            } catch (Exception e) {
              log.warn("Failed to get column name from annotation: {}", e.getMessage());
            }
            return stringHelper.getCamelCaseToSnakeCase(fieldName);
          }
        }
      }
    } catch (Exception e) {
      log.warn("Error while getting column name for field '{}': {}", fieldName, e.getMessage());
    }
    log.error("entity 에 포함되지 않는 필드 발견 : {}", fieldName);
    // 보안상 프로덕션 환경에서는 상세 정보 노출 방지
    if (log.isDebugEnabled()) {
      log.debug(
          "entity 필드 목록: {}",
          getAllNonExcludedFields(entityClass).stream()
              .map(Field::getName)
              .collect(Collectors.joining(", ")));
    }
    throw new MybatisRepositoryException("entity 에 포함되지 않는 필드 발견 : " + fieldName);
  }

  /** 클래스 계층을 타고 올라가며 동일한 이름의 필드를 찾음 */
  private Field findFieldInClassHierarchy(Class<?> clazz, String fieldName) {
    Class<?> curr = clazz;
    while (curr != null && curr != Object.class) {
      try {
        return curr.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        curr = curr.getSuperclass();
      }
    }
    return null;
  }

  /**
   * mapper 인터페이스에서 제네릭 타입으로 선언한 엔티티 클래스를 추출.
   *
   * <p>예: {@code public interface MyRepo extends MybatisRepository<User> { ... }}
   */
  @SuppressWarnings("unchecked")
  public <E> Class<E> extractEntityClassFromMapper(Class<?> mapperInterface) {
    // 프로바이더 메소드가 불릴 때마다, 즉 질의마다 도는 자리다. getGenericInterfaces() 는 부를 때마다 배열 사본을
    // 새로 만들고 부모 인터페이스까지 재귀로 훑는데, 매퍼와 엔티티의 짝은 끝까지 바뀌지 않는다.
    final Class<?> cached = MybatisCommand.MAPPER_ENTITY_CACHE.get(mapperInterface);
    if (cached != null) {
      return (Class<E>) cached;
    }
    final Class<E> resolved = resolveEntityClassFromMapper(mapperInterface);
    if (resolved != null) {
      // 못 찾으면 호출부가 예외로 끝내므로 성공한 것만 담는다(ConcurrentHashMap 은 null 을 담지도 못한다).
      MybatisCommand.MAPPER_ENTITY_CACHE.put(mapperInterface, resolved);
    }
    return resolved;
  }

  @SuppressWarnings("unchecked")
  private <E> Class<E> resolveEntityClassFromMapper(Class<?> mapperInterface) {
    Type[] genericIfs = mapperInterface.getGenericInterfaces();
    for (Type t : genericIfs) {
      if (t instanceof ParameterizedType) {
        ParameterizedType pt = (ParameterizedType) t;
        if (pt.getRawType() == MybatisRepository.class
            || pt.getRawType() == MybatisNoIdRepository.class) {
          Type actual = pt.getActualTypeArguments()[0];
          if (actual instanceof Class) {
            return (Class<E>) actual;
          }
        }
      }
    }
    // 부모 인터페이스 재귀 탐색
    for (Class<?> parentIf : mapperInterface.getInterfaces()) {
      Class<E> found = extractEntityClassFromMapper(parentIf);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
