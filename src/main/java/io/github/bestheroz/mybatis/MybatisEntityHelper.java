package io.github.bestheroz.mybatis;

import static io.github.bestheroz.mybatis.MybatisCommand.*;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MybatisEntityHelper {
  private static final Logger log = LoggerFactory.getLogger(MybatisEntityHelper.class);

  private final MybatisStringHelper stringHelper;

  public MybatisEntityHelper(MybatisStringHelper stringHelper) {
    this.stringHelper = stringHelper;
  }

  /** 현재 stacktrace 에서 유효한 element 를 찾아, 제네릭으로 선언된 클래스 타입을 파싱해 반환. */
  private Class<?> getEntityClass() {
    return Arrays.stream(new Throwable().getStackTrace())
        .filter(this::isValidStackTraceElement)
        .findFirst()
        .map(this::getClassFromStackTraceElement)
        .orElseThrow(() -> new MybatisRepositoryException("stackTraceElements is required"));
  }

  private boolean isValidStackTraceElement(final StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      return METHOD_LIST.contains(element.getMethodName())
          && clazz.getInterfaces().length > 0
          && clazz.getInterfaces()[0].getGenericInterfaces().length > 0;
    } catch (ClassNotFoundException e) {
      log.warn(MybatisStringHelper.getStackTrace(e));
      return false;
    }
  }

  private Class<?> getClassFromStackTraceElement(final StackTraceElement element) {
    try {
      Class<?> clazz = Class.forName(element.getClassName());
      String genericInterface = clazz.getInterfaces()[0].getGenericInterfaces()[0].getTypeName();
      String className = stringHelper.substringBetween(genericInterface, "<", ">");
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      log.warn(MybatisStringHelper.getStackTrace(e));
      throw new MybatisRepositoryException("Failed::ClassNotFoundException", e);
    }
  }

  /** 현재 Entity class 에 매핑된 Table name */
  protected String getTableName() {
    return getTableName(getEntityClass());
  }

  /** 특정 class 에 매핑된 Table name */
  protected String getTableName(final Class<?> entityClass) {
    if (TABLE_NAME_CACHE.containsKey(entityClass)) {
      return TABLE_NAME_CACHE.get(entityClass);
    }

    Table tableAnnotation = entityClass.getAnnotation(Table.class);
    String tableName;
    if (tableAnnotation != null && !tableAnnotation.name().isEmpty()) {
      tableName = tableAnnotation.name();
    } else {
      tableName = stringHelper.getCamelCaseToSnakeCase(entityClass.getSimpleName()).toLowerCase();
    }

    TABLE_NAME_CACHE.put(entityClass, tableName);
    return tableName;
  }

  protected Set<String> getEntityFields() {
    return getEntityFields(getEntityClass());
  }

  protected <T> Set<String> getEntityFields(final Class<T> entityClass) {
    return Stream.of(getAllNonExcludedFields(entityClass))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  protected static Field[] getAllNonExcludedFields(Class<?> clazz) {
    if (FIELD_CACHE.containsKey(clazz)) {
      return FIELD_CACHE.get(clazz);
    }

    List<Field> allFields = new ArrayList<>();
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      allFields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
      currentClass = currentClass.getSuperclass();
    }

    Field[] fieldArray =
        allFields.stream()
            .filter(field -> !MybatisProperties.getExcludeFields().contains(field.getName()))
            .distinct()
            .toArray(Field[]::new);
    FIELD_CACHE.put(clazz, fieldArray);
    return fieldArray;
  }

  protected String getColumnName(String fieldName) {
    // 'userName' -> 'user_name'
    return COLUMN_NAME_CACHE.computeIfAbsent(fieldName, stringHelper::getCamelCaseToSnakeCase);
  }
}
