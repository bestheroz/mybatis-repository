package io.github.bestheroz.mybatis;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
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

  /** 특정 클래스에 매핑된 Table name */
  protected String getTableName(final Class<?> entityClass) {
    if (MybatisCommand.TABLE_NAME_CACHE.containsKey(entityClass)) {
      return MybatisCommand.TABLE_NAME_CACHE.get(entityClass);
    }
    Table ann = entityClass.getAnnotation(Table.class);
    String tableName;
    if (ann != null && !ann.name().isEmpty()) {
      tableName = ann.name();
    } else {
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

  /** 모든 필드를 순회하여, @Column 어노테이션이 붙은 필드만 필터링 */
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
            .filter(field -> field.isAnnotationPresent(Column.class))
            .distinct()
            .toArray(Field[]::new);

    MybatisCommand.FIELD_CACHE.put(clazz, filtered);
    return filtered;
  }

  /** 특정 엔티티 클래스 + 자바 필드명을 받아서 DB 컬럼명으로 변환 */
  protected String getColumnName(final Class<?> entityClass, final String fieldName) {
    try {
      Field field = findFieldInClassHierarchy(entityClass, fieldName);
      if (field != null) {
        Column colAnn = field.getAnnotation(Column.class);
        if (colAnn != null && !colAnn.name().isEmpty()) {
          return colAnn.name();
        }
      }
    } catch (Exception e) {
      // 무시
    }
    return stringHelper.getCamelCaseToSnakeCase(fieldName);
  }

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
  // Utility: Mapper 인터페이스 → 엔티티 클래스
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
