package io.github.bestheroz.mybatis;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 엔티티(VO/DTO) 클래스에서 오직 @Column 어노테이션이 붙은 필드만 추출합니다. */
public class MybatisEntityHelper {
  private static final Logger log = LoggerFactory.getLogger(MybatisEntityHelper.class);
  private final MybatisStringHelper stringHelper;

  public MybatisEntityHelper(MybatisStringHelper stringHelper) {
    this.stringHelper = stringHelper;
  }

  /**
   * 특정 클래스에 매핑된 테이블 이름을 반환. - 엔티티 클래스에 @Table(name="...")이 붙어 있으면 그 값을 사용 - 없으면 클래스 이름을
   * CamelCase→snake_case 로 변환
   */
  protected String getTableName(final Class<?> entityClass) {
    if (MybatisCommand.TABLE_NAME_CACHE.containsKey(entityClass)) {
      return MybatisCommand.TABLE_NAME_CACHE.get(entityClass);
    }

    String tableName = null;

    // @Table 어노테이션이 붙어 있다면 name() 값을 읽어옴
    for (Annotation ann : entityClass.getAnnotations()) {
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
        } catch (Exception ignore) {
          // 없거나 예외 발생 시 무시
        }
      }
    }

    // @Table이 없거나 name()이 비어 있으면 CamelCase→snake_case
    if (tableName == null) {
      tableName = stringHelper.getCamelCaseToSnakeCase(entityClass.getSimpleName()).toLowerCase();
    }

    MybatisCommand.TABLE_NAME_CACHE.put(entityClass, tableName);
    return tableName;
  }

  /** 엔티티 클래스에 붙은 모든 @Column 어노테이션 필드명(자바 필드 이름) 집합을 반환. */
  protected Set<String> getEntityFields(final Class<?> entityClass) {
    return Stream.of(getAllNonExcludedFields(entityClass))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  /**
   * 클래스 계층을 순회하며 “실제 필드 레벨”에 @Column 어노테이션이 붙은 것만 필터링해서 리턴.
   *
   * <p>- jakarta.persistence.Column 또는 javax.persistence.Column 둘 다 처리 - 없으면 빈 배열 반환
   */
  protected static Field[] getAllNonExcludedFields(final Class<?> clazz) {
    if (MybatisCommand.FIELD_CACHE.containsKey(clazz)) {
      return MybatisCommand.FIELD_CACHE.get(clazz);
    }

    List<Field> allFields = new ArrayList<>();
    Class<?> current = clazz;
    while (current != null && current != Object.class) {
      allFields.addAll(Arrays.asList(current.getDeclaredFields()));
      current = current.getSuperclass();
    }

    Field[] filtered =
        allFields.stream()
            .filter(
                field -> {
                  // (1) 필드 레벨 @Column이 붙어 있는지 확인
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
            .toArray(Field[]::new);

    MybatisCommand.FIELD_CACHE.put(clazz, filtered);
    return filtered;
  }

  /**
   * 특정 엔티티 클래스와 자바 필드 이름을 받아 DB 컬럼명으로 변환. - 우선순위: @Column(name="...")이 붙어 있으면 name() → - 없으면
   * CamelCase→snake_case 로 변환
   */
  protected String getColumnName(final Class<?> entityClass, final String fieldName) {
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
              // reflection 호출 중 예외 발생 시 무시하고 다음 어노테이션 검사
            }
            return stringHelper.getCamelCaseToSnakeCase(fieldName);
          }
        }
      }
    } catch (Exception ignore) {
      // 필드 또는 리플렉션 중 오류 난 경우 무시
    }
    log.error("entity 에 포함되지 않는 필드 발견 : {}", fieldName);
    log.error(
        "entity 필드 목록: {}",
        Arrays.stream(getAllNonExcludedFields(entityClass))
            .map(Field::getName)
            .collect(Collectors.joining(", ")));
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
   * mapper 인터페이스에서 제네릭 타입으로 선언한 엔티티 클래스를 추출. 예: public interface MyRepo extends
   * MybatisRepository<User> { ... }
   */
  @SuppressWarnings("unchecked")
  public <E> Class<E> extractEntityClassFromMapper(Class<?> mapperInterface) {
    Type[] genericIfs = mapperInterface.getGenericInterfaces();
    for (Type t : genericIfs) {
      if (t instanceof ParameterizedType) {
        ParameterizedType pt = (ParameterizedType) t;
        if (pt.getRawType() == MybatisRepository.class) {
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
