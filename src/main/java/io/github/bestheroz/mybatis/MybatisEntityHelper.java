package io.github.bestheroz.mybatis;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MybatisEntityHelper {
  private final MybatisStringHelper stringHelper;

  public MybatisEntityHelper(MybatisStringHelper stringHelper) {
    this.stringHelper = stringHelper;
  }

  /**
   * 특정 클래스에 매핑된 Table name을 리턴. jakarta.persistence.Table 또는 javax.persistence.Table 어노테이션이 존재할 경우
   * name() 속성을 사용하고, 둘 다 없으면 CamelCase → snake_case 로 변환.
   */
  protected String getTableName(final Class<?> entityClass) {
    if (MybatisCommand.TABLE_NAME_CACHE.containsKey(entityClass)) {
      return MybatisCommand.TABLE_NAME_CACHE.get(entityClass);
    }

    String tableName = null;

    // (1) 클래스에 붙은 모든 어노테이션을 순회하며 @Table 찾기
    for (Annotation ann : entityClass.getAnnotations()) {
      String annType = ann.annotationType().getName();
      if (annType.equals("jakarta.persistence.Table")
          || annType.equals("javax.persistence.Table")) {
        try {
          // name() 메서드가 있으면 호출해서 값 가져오기
          Method nameMethod = ann.annotationType().getMethod("name");
          Object value = nameMethod.invoke(ann);
          if (value instanceof String) {
            String nameValue = (String) value;
            if (!nameValue.isEmpty()) {
              tableName = nameValue;
              break;
            }
          }
        } catch (Exception e) {
          // reflection 호출 중 예외 발생 시 무시하고 다음 어노테이션 검사
        }
      }
    }

    // (2) @Table이 없거나 name()이 비어 있으면 CamelCase → snake_case 변환
    if (tableName == null) {
      tableName = stringHelper.getCamelCaseToSnakeCase(entityClass.getSimpleName()).toLowerCase();
    }

    MybatisCommand.TABLE_NAME_CACHE.put(entityClass, tableName);
    return tableName;
  }

  /** 엔티티 클래스의 모든 (@Column이 붙은) 필드 이름 집합 */
  protected Set<String> getEntityFields(final Class<?> entityClass) {
    return Stream.of(getAllNonExcludedFields(entityClass))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  /**
   * 모든 필드를 순회하여, @Column 어노테이션이 붙은 필드만 필터링. jakarta.persistence.Column 또는 javax.persistence.Column이
   * 클래스패스에 없더라도 안전하게 동작.
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
                  // (1) 해당 필드에 붙은 모든 어노테이션을 확인
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
   * 특정 엔티티 클래스 + 자바 필드명을 받아서 DB 컬럼명으로 변환. @Column(name="...")을 우선으로, 없으면 CamelCase → snake_case 로
   * 변환.
   */
  protected String getColumnName(final Class<?> entityClass, final String fieldName) {
    try {
      Field field = findFieldInClassHierarchy(entityClass, fieldName);
      if (field != null) {
        // (1) 해당 필드에 붙은 모든 어노테이션을 순회하며 @Column 찾기
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
          }
        }
      }
    } catch (Exception ignore) {
      // 필드 탐색/리플렉션 중 예외가 발생해도 무시하고 CamelCase → snake_case 로 fallback
    }

    // (2) @Column이 없거나 name()이 비어 있으면 CamelCase → snake_case
    return stringHelper.getCamelCaseToSnakeCase(fieldName);
  }

  /** 클래스 계층 구조를 타고 올라가면서 동일한 이름의 필드를 찾음 */
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

  // ===========================================
  // Utility: Mapper 인터페이스 → 엔티티 클래스 추출
  // ===========================================
  @SuppressWarnings("unchecked")
  public <E> Class<E> extractEntityClassFromMapper(Class<?> mapperInterface) {
    // (1) mapperInterface가 implements한 Generic Interfaces 확인
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
    // (2) 부모 인터페이스 재귀 탐색
    for (Class<?> parentIf : mapperInterface.getInterfaces()) {
      Class<E> found = extractEntityClassFromMapper(parentIf);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
