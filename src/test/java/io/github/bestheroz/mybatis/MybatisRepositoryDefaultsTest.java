package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.ibatis.builder.annotation.ProviderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code default} 메소드 37개는 그동안 한 줄도 실행되지 않았다(라인 커버리지 0%). SQL 을 만들지 않고 7개 프로바이더 메소드로 넘기기만 하는 얇은
 * 껍데기지만, 넘기면서 빈 컬렉션을 채우고 null 을 정규화하므로 인자 자리가 뒤바뀌거나 null 가드가 빠져도 아무 테스트도 깨지지 않았다.
 *
 * <p>예전에는 이 껍데기가 {@link MybatisRepository} 와 {@link MybatisNoIdRepository} 에 통째로 두 벌 있었고, 이 테스트는 두
 * 벌이 어긋나지 않았는지 맞대어 보는 것이 주된 일이었다. 지금은 35개가 {@link MybatisRepositoryBase} 한 곳에 있어 어긋날 자리가 없고, 각
 * 인터페이스에 남은 것은 insert 껍데기 두 개뿐이다. 맞대어 보기는 그 두 개를 지키는 일로 좁아졌고, 대신 껍데기 37개를 실제로 실행해 인자 자리와 null 정규화를
 * 확인하는 역할이 남는다.
 *
 * <p>{@link #sharedDefaults_ShouldBeDeclaredOnceOnTheBaseInterface} 는 그 구조 자체를 못박는다 -- 누군가 공용 껍데기를
 * 하위 인터페이스로 다시 복사해 오면 거기서 걸린다.
 */
class MybatisRepositoryDefaultsTest {

  @Table(name = "test_user")
  static class TestUser {
    @Column(name = "user_id")
    private Long userId;

    @Column private String name;

    /** 기록을 문자열로 맞대어 보므로 신원 해시가 아니라 값으로 찍혀야 한다. */
    @Override
    public String toString() {
      return "TestUser(" + userId + ", " + name + ")";
    }
  }

  /** 프로바이더 메소드가 받은 인자를 그대로 담아 둔다. */
  static final class Call {
    private String method;
    private Object[] args;

    private void record(final String method, final Object... args) {
      this.method = method;
      this.args = args;
    }

    private String describe() {
      return method + Arrays.deepToString(args);
    }
  }

  /** 두 스텁이 공통으로 노출하는 것은 기록뿐이다. 인터페이스 자체는 서로 관계가 없어 공통 상위 타입이 없으므로, default 메소드는 리플렉션으로 부른다. */
  interface Recording {
    Call call();
  }

  static class IdRepo implements MybatisRepository<TestUser>, Recording {
    private final Call call = new Call();

    @Override
    public Call call() {
      return call;
    }

    @Override
    public List<TestUser> buildSelectSQL(
        ProviderContext context,
        Set<String> distinctColumns,
        Set<String> targetColumns,
        Map<String, Object> whereConditions,
        List<String> orderByConditions,
        Integer limit,
        Integer offset) {
      call.record(
          "select",
          distinctColumns,
          targetColumns,
          whereConditions,
          orderByConditions,
          limit,
          offset);
      return Collections.emptyList();
    }

    @Override
    public Optional<TestUser> buildSelectOneSQL(
        ProviderContext context, Map<String, Object> whereConditions) {
      call.record("selectOne", whereConditions);
      return Optional.empty();
    }

    @Override
    public long buildCountSQL(ProviderContext context, Map<String, Object> whereConditions) {
      call.record("count", whereConditions);
      return 0L;
    }

    @Override
    public void buildInsertSQL(TestUser entity) {
      call.record("insert", entity);
    }

    @Override
    public void buildInsertBatchSQL(List<TestUser> entities) {
      call.record("insertBatch", entities);
    }

    @Override
    public void buildUpdateSQL(
        ProviderContext context,
        Map<String, Object> updateMap,
        Map<String, Object> whereConditions) {
      call.record("update", updateMap, whereConditions);
    }

    @Override
    public void buildDeleteSQL(ProviderContext context, Map<String, Object> whereConditions) {
      call.record("delete", whereConditions);
    }
  }

  static class NoIdRepo implements MybatisNoIdRepository<TestUser>, Recording {
    private final Call call = new Call();

    @Override
    public Call call() {
      return call;
    }

    @Override
    public List<TestUser> buildSelectSQL(
        ProviderContext context,
        Set<String> distinctColumns,
        Set<String> targetColumns,
        Map<String, Object> whereConditions,
        List<String> orderByConditions,
        Integer limit,
        Integer offset) {
      call.record(
          "select",
          distinctColumns,
          targetColumns,
          whereConditions,
          orderByConditions,
          limit,
          offset);
      return Collections.emptyList();
    }

    @Override
    public Optional<TestUser> buildSelectOneSQL(
        ProviderContext context, Map<String, Object> whereConditions) {
      call.record("selectOne", whereConditions);
      return Optional.empty();
    }

    @Override
    public long buildCountSQL(ProviderContext context, Map<String, Object> whereConditions) {
      call.record("count", whereConditions);
      return 0L;
    }

    @Override
    public void buildInsertSQL(TestUser entity) {
      call.record("insert", entity);
    }

    @Override
    public void buildInsertBatchSQL(List<TestUser> entities) {
      call.record("insertBatch", entities);
    }

    @Override
    public void buildUpdateSQL(
        ProviderContext context,
        Map<String, Object> updateMap,
        Map<String, Object> whereConditions) {
      call.record("update", updateMap, whereConditions);
    }

    @Override
    public void buildDeleteSQL(ProviderContext context, Map<String, Object> whereConditions) {
      call.record("delete", whereConditions);
    }
  }

  /**
   * 파라미터 타입만 보고 인자를 만든다. 같은 타입이 두 번 나오면 서로 다른 값을 주는 것이 핵심이다 -- distinct/target 처럼 나란히 붙은 {@code
   * Set} 두 개나 limit/offset 처럼 붙은 {@code Integer} 두 개가 뒤바뀌어도, 값이 같으면 아무 테스트도 깨지지 않는다.
   */
  private static Object[] argumentsFor(final Method method, final boolean allNull) {
    final Object[] args = new Object[method.getParameterCount()];
    if (allNull) {
      // 파라미터가 모두 참조 타입이라 그대로 null 을 넣을 수 있다.
      return args;
    }
    int sets = 0;
    int maps = 0;
    int lists = 0;
    int integers = 0;
    for (int i = 0; i < args.length; i++) {
      final Class<?> type = method.getParameterTypes()[i];
      if (Set.class.equals(type)) {
        args[i] = new LinkedHashSet<>(Collections.singletonList("set" + sets++));
      } else if (Map.class.equals(type)) {
        args[i] = Collections.<String, Object>singletonMap("map" + maps++, 1);
      } else if (List.class.equals(type)) {
        args[i] = Collections.singletonList("list" + lists++);
      } else if (Integer.class.equals(type)) {
        args[i] = 10 + integers++;
      } else if (Long.class.equals(type)) {
        args[i] = 7L;
      } else {
        args[i] = new TestUser();
      }
    }
    return args;
  }

  /**
   * {@code default} 로 선언된, 즉 실제로 검사할 메소드만 이름 순으로 모은다. 공용 껍데기가 {@link MybatisRepositoryBase} 로
   * 내려갔으므로 상위 인터페이스까지 함께 훑는다 -- 소비자가 매퍼에서 실제로 부를 수 있는 껍데기 전부가 대상이다.
   */
  private static List<Method> defaultMethodsOf(final Class<?> repositoryInterface) {
    final List<Method> methods = new ArrayList<>();
    collectDefaultMethods(repositoryInterface, methods);
    methods.sort(
        (left, right) -> {
          final int byName = left.getName().compareTo(right.getName());
          return byName != 0 ? byName : left.toString().compareTo(right.toString());
        });
    return methods;
  }

  private static void collectDefaultMethods(final Class<?> type, final List<Method> into) {
    for (Method method : type.getDeclaredMethods()) {
      if (method.isDefault()) {
        into.add(method);
      }
    }
    for (Class<?> parent : type.getInterfaces()) {
      collectDefaultMethods(parent, into);
    }
  }

  @Test
  @DisplayName("공용 껍데기는 상위 인터페이스에만 선언되어야 한다")
  void sharedDefaults_ShouldBeDeclaredOnceOnTheBaseInterface() {
    // given / when / then
    // 두 인터페이스에 남아도 되는 껍데기는 insert 두 개뿐이다. 나머지가 여기 다시 나타나면
    // 838줄을 한 곳으로 모은 것이 원상복구되는 중이라는 뜻이다.
    assertThat(declaredDefaultNamesOf(MybatisRepository.class))
        .containsExactlyInAnyOrder("insert", "insertBatch");
    assertThat(declaredDefaultNamesOf(MybatisNoIdRepository.class))
        .containsExactlyInAnyOrder("insert", "insertBatch");
    assertThat(declaredDefaultNamesOf(MybatisRepositoryBase.class)).hasSize(35);
  }

  private static Set<String> declaredDefaultNamesOf(final Class<?> repositoryInterface) {
    final Set<String> names = new TreeSet<>();
    for (Method method : repositoryInterface.getDeclaredMethods()) {
      if (method.isDefault()) {
        names.add(method.getName());
      }
    }
    return names;
  }

  @Test
  @DisplayName("두 저장소 인터페이스의 default 메소드는 같은 프로바이더 호출로 이어져야 한다")
  void defaultMethods_ShouldDelegateIdenticallyInBothInterfaces() throws Exception {
    // given
    // 35개는 공용 상위 인터페이스에서 오고 2개는 각자 선언한 insert 껍데기다. 어느 쪽으로 부르든
    // 같은 프로바이더 호출로 이어져야 한다.
    List<Method> idMethods = defaultMethodsOf(MybatisRepository.class);
    List<Method> noIdMethods = defaultMethodsOf(MybatisNoIdRepository.class);

    // then
    assertThat(idMethods).hasSize(37);
    assertThat(signaturesOf(noIdMethods)).isEqualTo(signaturesOf(idMethods));

    IdRepo idRepo = new IdRepo();
    NoIdRepo noIdRepo = new NoIdRepo();

    for (int i = 0; i < idMethods.size(); i++) {
      final Method idMethod = idMethods.get(i);
      final Method noIdMethod = noIdMethods.get(i);

      // when / then
      // 인자를 모두 채운 경로. 자리 바뀜을 잡는다.
      assertThat(invokeAndDescribe(noIdMethod, noIdRepo, argumentsFor(noIdMethod, false)))
          .describedAs("%s 가 두 인터페이스에서 다르게 위임한다", idMethod.getName())
          .isEqualTo(invokeAndDescribe(idMethod, idRepo, argumentsFor(idMethod, false)));

      // 인자가 모두 null 인 경로. 껍데기의 null 정규화(`x == null ? emptyX() : x`)가 한쪽에만
      // 있으면 여기서만 드러난다 -- 채운 인자로는 그 분기를 아예 지나지 않는다.
      assertThat(invokeAndDescribe(noIdMethod, noIdRepo, argumentsFor(noIdMethod, true)))
          .describedAs("%s 의 null 처리가 두 인터페이스에서 다르다", idMethod.getName())
          .isEqualTo(invokeAndDescribe(idMethod, idRepo, argumentsFor(idMethod, true)));
    }
  }

  /**
   * 메소드를 부르고 기록된 프로바이더 호출을 문자열로 돌려준다. 예외로 끝나면 예외 종류를 결과로 삼는다 -- 한쪽만 던지는 것도 두 인터페이스의 차이이므로 함께 맞대어 봐야
   * 한다.
   */
  private static String invokeAndDescribe(
      final Method method, final Recording target, final Object[] args) {
    try {
      method.invoke(target, args);
    } catch (final InvocationTargetException e) {
      return "threw " + e.getCause().getClass().getName();
    } catch (final IllegalAccessException e) {
      throw new AssertionError(e);
    }
    return target.call().describe();
  }

  private static Set<String> signaturesOf(final List<Method> methods) {
    final Set<String> signatures = new TreeSet<>();
    for (Method method : methods) {
      signatures.add(
          method.getName()
              + Arrays.toString(method.getParameterTypes())
              + " -> "
              + method.getReturnType().getName());
    }
    return signatures;
  }

  @Test
  @DisplayName("인자 없는 조회는 빈 컬렉션과 null 페이징으로 위임해야 한다")
  void getItems_ShouldPassEmptyCollections() {
    // given
    IdRepo repo = new IdRepo();

    // when
    repo.getItems();

    // then
    assertThat(repo.call().describe()).isEqualTo("select[[], [], {}, [], null, null]");
  }

  @Test
  @DisplayName("distinct 와 target 은 서로 다른 자리로 넘어가야 한다")
  void getDistinctAndTargetItems_ShouldNotSwapColumnSets() {
    // given
    // 두 인자 모두 Set<String> 이라 자리가 바뀌어도 컴파일은 된다. 값을 달리해 자리를 고정한다.
    IdRepo repo = new IdRepo();
    Set<String> distinct = Collections.singleton("distinctCol");
    Set<String> target = Collections.singleton("targetCol");

    // when
    repo.getDistinctAndTargetItemsByMapOrderByLimitOffset(
        distinct, target, new HashMap<>(), Collections.singletonList("name"), 10, 20);

    // then
    assertThat(repo.call().describe())
        .isEqualTo("select[[distinctCol], [targetCol], {}, [name], 10, 20]");
  }

  @Test
  @DisplayName("null 로 넘어온 조건과 정렬은 빈 컬렉션으로 정규화해야 한다")
  void defaultMethods_ShouldNormalizeNullCollections() {
    // given
    IdRepo repo = new IdRepo();

    // when
    repo.getItemsOrderBy(null);

    // then
    // 프로바이더 쪽은 null 컬렉션을 받도록 만들어져 있지 않다. 껍데기가 여기서 걸러 준다.
    assertThat(repo.call().describe()).isEqualTo("select[[], [], {}, [], null, null]");

    // when
    repo.getItemsByMap(null);

    // then
    assertThat(repo.call().describe()).isEqualTo("select[[], [], {}, [], null, null]");
  }

  @Test
  @DisplayName("id 로 접근하는 메소드는 자바 필드명 id 를 조건으로 만들어야 한다")
  void byId_ShouldBuildIdCondition() {
    // given
    // @Id 는 읽지 않고 자바 필드명 "id" 를 그대로 쓴다. 이 규약이 바뀌면 여기서 드러난다.
    IdRepo repo = new IdRepo();

    // when / then
    repo.getItemById(7L);
    assertThat(repo.call().describe()).isEqualTo("selectOne[{id=7}]");

    repo.deleteById(7L);
    assertThat(repo.call().describe()).isEqualTo("delete[{id=7}]");

    repo.updateMapById(Collections.<String, Object>singletonMap("name", null), 7L);
    assertThat(repo.call().describe()).isEqualTo("update[{name=null}, {id=7}]");
  }

  @Test
  @DisplayName("엔티티로 갱신하면 값이 null 인 필드까지 updateMap 에 담겨야 한다")
  void updateById_ShouldKeepNullFieldsInUpdateMap() {
    // given
    // 껍데기 단계에서 null 필드를 걸러 내면 "null 이면 NULL 로 갱신" 규약이 여기서부터 깨진다.
    IdRepo repo = new IdRepo();
    TestUser entity = new TestUser();
    entity.userId = 7L;
    // name 은 null 로 둔다

    // when
    repo.updateById(entity, 7L);

    // then
    assertThat(repo.call().describe()).contains("name=null").contains("userId=7");
  }

  @Test
  @DisplayName("countAll 은 빈 조건으로, insertBatch 는 받은 목록 그대로 위임해야 한다")
  void countAllAndInsertBatch_ShouldDelegateAsIs() {
    // given
    IdRepo repo = new IdRepo();
    List<TestUser> entities = Collections.singletonList(new TestUser());

    // when / then
    repo.countAll();
    assertThat(repo.call().describe()).isEqualTo("count[{}]");

    repo.insertBatch(entities);
    assertThat(repo.call().args[0]).isSameAs(entities);
  }
}
