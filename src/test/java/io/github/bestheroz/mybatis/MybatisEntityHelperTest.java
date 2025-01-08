package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.bestheroz.mybatis.exception.MybatisRepositoryException;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisEntityHelperTest {

  interface BaseRepository<T> {}

  interface EntityRepository<T> extends BaseRepository<T> {}

  static class ValidRepository implements EntityRepository<TestEntity> {}

  static class InvalidRepository implements BaseRepository<TestEntity> {}

  static class NoInterfaceRepository {}

  static class TestEntity {}

  private static Set<String> originalExcludeFields;
  private MybatisProperties mybatisProperties;

  @Mock private MybatisStringHelper stringHelper;
  private MybatisEntityHelper entityHelper;

  @BeforeEach
  void setUp() {
    entityHelper = new MybatisEntityHelper(stringHelper);
    mybatisProperties = new MybatisProperties();
    Set<String> excludeFields = MybatisProperties.getExcludeFields();
    excludeFields.add("__$hits$__");
    excludeFields.add("$jacocoData");
    originalExcludeFields = new HashSet<>(excludeFields);
  }

  @AfterEach
  void tearDown() {
    mybatisProperties.setExcludeFields(originalExcludeFields);
    MybatisCommand.FIELD_CACHE.clear();
  }

  // ... (기존 테스트 코드)

  @Test
  @DisplayName("유효한 스택트레이스 요소에서 메소드명이 METHOD_LIST에 포함되어 있고 인터페이스 조건을 만족하면 true를 반환해야 한다")
  void isValidStackTraceElement_WithValidElement_ShouldReturnTrue() throws Exception {
    // given
    StackTraceElement validElement =
        new StackTraceElement(
            ValidRepository.class.getName(),
            MybatisCommand.SELECT_ITEMS,
            "ValidRepository.java",
            100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "isValidStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when
    boolean result = (boolean) method.invoke(entityHelper, validElement);

    // then
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("메소드명이 METHOD_LIST에 없으면 false를 반환해야 한다")
  void isValidStackTraceElement_WithInvalidMethod_ShouldReturnFalse() throws Exception {
    // given
    StackTraceElement invalidMethodElement =
        new StackTraceElement(
            ValidRepository.class.getName(), "invalidMethod", "ValidRepository.java", 100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "isValidStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when
    boolean result = (boolean) method.invoke(entityHelper, invalidMethodElement);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("인터페이스가 없는 클래스의 스택트레이스 요소는 false를 반환해야 한다")
  void isValidStackTraceElement_WithNoInterface_ShouldReturnFalse() throws Exception {
    // given
    StackTraceElement noInterfaceElement =
        new StackTraceElement(
            NoInterfaceRepository.class.getName(),
            MybatisCommand.SELECT_ITEMS,
            "NoInterfaceRepository.java",
            100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "isValidStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when
    boolean result = (boolean) method.invoke(entityHelper, noInterfaceElement);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("제네릭 인터페이스가 없는 클래스의 스택트레이스 요소는 false를 반환해야 한다")
  void isValidStackTraceElement_WithNoGenericInterface_ShouldReturnFalse() throws Exception {
    // given
    StackTraceElement invalidElement =
        new StackTraceElement(
            InvalidRepository.class.getName(),
            MybatisCommand.SELECT_ITEMS,
            "InvalidRepository.java",
            100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "isValidStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when
    boolean result = (boolean) method.invoke(entityHelper, invalidElement);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("존재하지 않는 클래스의 스택트레이스 요소는 false를 반환해야 한다")
  void isValidStackTraceElement_WithNonExistentClass_ShouldReturnFalse()
      throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
    // given
    StackTraceElement nonExistentElement =
        new StackTraceElement("non.existent.Class", "selectOne", "NonExistent.java", 100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "isValidStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when
    boolean result = (boolean) method.invoke(entityHelper, nonExistentElement);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("유효한 스택트레이스 요소에서 제네릭 타입을 정확히 추출해야 한다")
  void getClassFromStackTraceElement_WithValidElement_ShouldExtractGenericType() throws Exception {
    // given
    StackTraceElement validElement =
        new StackTraceElement(
            ValidRepository.class.getName(),
            MybatisCommand.SELECT_ITEMS,
            "ValidRepository.java",
            100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "getClassFromStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // ValidRepository 클래스의 실제 제네릭 인터페이스 타입을 가져옵니다
    Class<?> clazz = Class.forName(validElement.getClassName());
    String actualGenericInterface =
        clazz.getInterfaces()[0].getGenericInterfaces()[0].getTypeName();

    when(stringHelper.substringBetween(actualGenericInterface, "<", ">"))
        .thenReturn(TestEntity.class.getName());

    // when
    Class<?> result = (Class<?>) method.invoke(entityHelper, validElement);

    // then
    assertThat(result).isEqualTo(TestEntity.class);
  }

  @Test
  @DisplayName("존재하지 않는 클래스의 스택트레이스 요소에서 ClassNotFoundException이 발생해야 한다")
  void getClassFromStackTraceElement_WithNonExistentClass_ShouldThrowException()
      throws NoSuchMethodException {
    // given
    StackTraceElement nonExistentElement =
        new StackTraceElement("non.existent.Class", "selectOne", "NonExistent.java", 100);
    Method method =
        MybatisEntityHelper.class.getDeclaredMethod(
            "getClassFromStackTraceElement", StackTraceElement.class);
    method.setAccessible(true);

    // when & then
    assertThatThrownBy(() -> method.invoke(entityHelper, nonExistentElement))
        .isInstanceOf(InvocationTargetException.class)
        .getCause()
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("Failed::ClassNotFoundException");
  }

  @Table(name = "test_table")
  static class TestEntityWithTableAnnotation {
    private String id;
    private String name;
  }

  static class TestEntityWithoutTableAnnotation {
    private String id;
    private String name;
  }

  static class TestEntityWithInheritance extends TestEntityWithTableAnnotation {
    private String description;
  }

  @Test
  @DisplayName("getEntityClass 메소드는 유효하지 않은 스택트레이스에서 예외를 발생시켜야 한다")
  void getEntityClass_WithInvalidStackTrace_ShouldThrowException() throws NoSuchMethodException {
    // given
    Method method = MybatisEntityHelper.class.getDeclaredMethod("getEntityClass");
    method.setAccessible(true);

    // when & then
    assertThatThrownBy(() -> method.invoke(entityHelper))
        .isInstanceOf(InvocationTargetException.class)
        .getCause()
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("stackTraceElements is required");
  }

  @Test
  @DisplayName("Table 어노테이션이 있는 엔티티의 테이블 이름을 정확히 가져와야 한다")
  void getTableName_WithTableAnnotation_ShouldReturnAnnotationValue() {
    // when
    String tableName = entityHelper.getTableName(TestEntityWithTableAnnotation.class);

    // then
    assertThat(tableName).isEqualTo("test_table");
  }

  @Test
  @DisplayName("Table 어노테이션이 없는 엔티티의 테이블 이름을 클래스명으로부터 생성해야 한다")
  void getTableName_WithoutTableAnnotation_ShouldGenerateFromClassName() {
    // given
    when(stringHelper.getCamelCaseToSnakeCase("TestEntityWithoutTableAnnotation"))
        .thenReturn("test_entity_without_table_annotation");

    // when
    String tableName = entityHelper.getTableName(TestEntityWithoutTableAnnotation.class);

    // then
    assertThat(tableName).isEqualTo("test_entity_without_table_annotation");
  }

  @Test
  @DisplayName("엔티티의 모든 필드를 정확히 가져와야 한다")
  void getEntityFields_ShouldReturnAllFields() {
    // when
    Set<String> fields = entityHelper.getEntityFields(TestEntityWithTableAnnotation.class);

    // then
    assertThat(fields).containsExactlyInAnyOrder("id", "name");
  }

  @Test
  @DisplayName("상속받은 필드를 포함한 모든 필드를 가져와야 한다")
  void getEntityFields_WithInheritance_ShouldReturnAllFields() {
    // when
    Set<String> fields = entityHelper.getEntityFields(TestEntityWithInheritance.class);

    // then
    assertThat(fields).containsExactlyInAnyOrder("id", "name", "description");
  }

  @Test
  @DisplayName("제외된 필드를 제외한 모든 필드를 가져와야 한다")
  void getAllNonExcludedFields_ShouldExcludeSpecifiedFields() {
    // given
    Set<String> excludeFields = new HashSet<>();
    excludeFields.add("name");
    excludeFields.add("__$hits$__");
    excludeFields.add("$jacocoData");
    excludeFields = Collections.unmodifiableSet(excludeFields);
    mybatisProperties.setExcludeFields(excludeFields);

    // when
    Field[] fields =
        MybatisEntityHelper.getAllNonExcludedFields(TestEntityWithTableAnnotation.class);

    // then
    assertThat(fields).extracting(Field::getName).containsExactly("id");
  }

  @Test
  @DisplayName("필드명을 컬럼명으로 정확히 변환해야 한다")
  void getColumnName_ShouldConvertFieldNameToColumnName() {
    // given
    String fieldName = "userName";
    when(stringHelper.getCamelCaseToSnakeCase(fieldName)).thenReturn("user_name");

    // when
    String columnName = entityHelper.getColumnName(fieldName);

    // then
    assertThat(columnName).isEqualTo("user_name");
  }

  @Test
  @DisplayName("캐시된 테이블 이름을 재사용해야 한다")
  void getTableName_ShouldReuseCache() {
    // when
    String firstCall = entityHelper.getTableName(TestEntityWithTableAnnotation.class);
    String secondCall = entityHelper.getTableName(TestEntityWithTableAnnotation.class);

    // then
    assertThat(firstCall).isEqualTo(secondCall).isEqualTo("test_table");
  }

  // 테스트용 스택트레이스 시뮬레이션 메소드 추가
  private void simulateStackTrace(Class<?> repositoryClass, String methodName) {
    StackTraceElement[] stackTrace = new Throwable().getStackTrace();
    StackTraceElement mockElement =
        new StackTraceElement(
            repositoryClass.getName(), methodName, repositoryClass.getSimpleName() + ".java", 1);
    // 기존 스택트레이스의 첫번째 요소를 mock 요소로 교체
    stackTrace[0] = mockElement;
    new Throwable().setStackTrace(stackTrace);
  }

  @Test
  @DisplayName("getTableName 메소드는 유효하지 않은 스택트레이스에서 예외를 발생시켜야 한다")
  void getTableName_WithInvalidStackTrace_ShouldThrowException() {
    // given
    simulateStackTrace(NoInterfaceRepository.class, "invalidMethod");

    // when & then
    assertThatThrownBy(() -> entityHelper.getTableName())
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("stackTraceElements is required");
  }

  @Test
  @DisplayName("getEntityFields 메소드는 유효하지 않은 스택트레이스에서 예외를 발생시켜야 한다")
  void getEntityFields_WithInvalidStackTrace_ShouldThrowException() {
    // given
    simulateStackTrace(NoInterfaceRepository.class, "invalidMethod");

    // when & then
    assertThatThrownBy(() -> entityHelper.getEntityFields())
        .isInstanceOf(MybatisRepositoryException.class)
        .hasMessageContaining("stackTraceElements is required");
  }
}
