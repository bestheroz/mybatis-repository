package io.github.bestheroz.mybatis;

import java.util.*;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.builder.annotation.ProviderContext;

/**
 * {@link MybatisRepository} 와 {@link MybatisNoIdRepository} 가 함께 쓰는 조회/집계/수정/삭제 기능.
 *
 * <p>두 인터페이스는 insert 두 곳의 {@code @Options(useGeneratedKeys = true, keyProperty = "id")} 하나만 다르고
 * 나머지는 글자까지 같았다. 838줄, 전체 소스의 37%가 같은 내용을 두 번 적은 것이었고, "한쪽을 고치면 반드시 다른 쪽도 고쳐라" 라는 규칙과 그 규칙을 지키는지
 * 확인하는 테스트까지 따로 필요했다. 다른 부분만 각자 두고 같은 부분은 여기로 모은다.
 *
 * <p>insert 두 개를 여기로 올리지 않은 이유가 핵심이다. 어노테이션이 붙지 않은 추상 메소드를 여기 두고 하위 인터페이스에서 다시 선언하면, MyBatis 가 매퍼를
 * 훑을 때({@code MapperAnnotationBuilder}) 어느 선언을 보게 되는지가 JDK 의 메소드 해소 규칙에 달리게 된다. 어노테이션이 붙은 선언을 한 곳에만
 * 두면 그 문제가 아예 생기지 않으므로, insert 프로바이더와 그 껍데기 두 개만 각 하위 인터페이스에 남긴다.
 *
 * <p>{@code default} 메소드는 MyBatis 가 문장으로 파싱하지 않고({@code canHaveStatement} 가 걸러 낸다), 어노테이션이 붙은 추상
 * 메소드는 상위 인터페이스에 있어도 {@code Class#getMethods} 에 그대로 나오므로 매퍼 등록 결과는 나누기 전과 같다. {@code
 * MybatisMapperRegistrationTest} 가 실제 {@code Configuration} 에 매퍼를 등록해 이 점을 확인한다.
 *
 * @param <T> 엔티티 타입
 */
public interface MybatisRepositoryBase<T> {
  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.SELECT_ITEMS)
  List<T> buildSelectSQL(
      ProviderContext context,
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset);

  default List<T> getItems() {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getItemsLimitOffset(final Integer limit, final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getItemsOrderBy(final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getItemsOrderByLimitOffset(
      final List<String> orderByConditions, final Integer limit, final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getItemsByMap(final Map<String, Object> whereConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getItemsByMapLimitOffset(
      final Map<String, Object> whereConditions, final Integer limit, final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getItemsByMapOrderBy(
      final Map<String, Object> whereConditions, final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getItemsByMapOrderByLimitOffset(
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getDistinctItems(final Set<String> distinctColumns) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getDistinctItemsLimitOffset(
      final Set<String> distinctColumns, final Integer limit, final Integer offset) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getDistinctItemsOrderBy(
      final Set<String> distinctColumns, final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getDistinctItemsOrderByLimitOffset(
      final Set<String> distinctColumns,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getDistinctItemsByMap(
      final Set<String> distinctColumns, final Map<String, Object> whereConditions) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getDistinctItemsByMapLimitOffset(
      final Set<String> distinctColumns,
      final Map<String, Object> whereConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getDistinctItemsByMapOrderBy(
      final Set<String> distinctColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getDistinctItemsByMapOrderByLimitOffset(
      final Set<String> distinctColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        Collections.emptySet(),
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getTargetItems(final Set<String> targetColumns) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        Collections.emptyMap(),
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getTargetItemsLimitOffset(
      final Set<String> targetColumns, final Integer limit, final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        Collections.emptyMap(),
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getTargetItemsOrderBy(
      final Set<String> targetColumns, final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getTargetItemsOrderByLimitOffset(
      final Set<String> targetColumns,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        Collections.emptyMap(),
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getTargetItemsByMap(
      final Set<String> targetColumns, final Map<String, Object> whereConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        null,
        null);
  }

  default List<T> getTargetItemsByMapLimitOffset(
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        Collections.emptyList(),
        limit,
        offset);
  }

  default List<T> getTargetItemsByMapOrderBy(
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        null,
        null);
  }

  default List<T> getTargetItemsByMapOrderByLimitOffset(
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        Collections.emptySet(),
        targetColumns == null ? Collections.emptySet() : targetColumns,
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  default List<T> getDistinctAndTargetItemsByMapOrderByLimitOffset(
      final Set<String> distinctColumns,
      final Set<String> targetColumns,
      final Map<String, Object> whereConditions,
      final List<String> orderByConditions,
      final Integer limit,
      final Integer offset) {
    return this.buildSelectSQL(
        null,
        // 나머지 36개 껍데기와 달리 여기만 null 정규화가 빠져 있었다. 아래쪽(appendSelectColumns)이
        // null 을 따로 검사하고 있어 드러나지는 않았지만, 프로바이더가 빈 컬렉션을 받는다는 전제를
        // 이 메소드만 지키지 않는 상태였다.
        distinctColumns == null ? Collections.emptySet() : distinctColumns,
        targetColumns == null ? Collections.emptySet() : targetColumns,
        whereConditions == null ? Collections.emptyMap() : whereConditions,
        orderByConditions == null ? Collections.emptyList() : orderByConditions,
        limit,
        offset);
  }

  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.SELECT_ITEM_BY_MAP)
  Optional<T> buildSelectOneSQL(ProviderContext context, final Map<String, Object> whereConditions);

  default Optional<T> getItemByMap(final Map<String, Object> whereConditions) {
    // 형제 껍데기 34개와 같이 null 을 빈 맵으로 정규화한다. 여기 셋만 빠져 있었고, 그 결과 null 이
    // MyBatis 의 ParamMap 안까지 실려 가 그 안의 키(context 등)가 엔티티 필드 이름으로 읽혔다.
    return this.buildSelectOneSQL(
        null, whereConditions == null ? Collections.emptyMap() : whereConditions);
  }

  default Optional<T> getItemById(final Long id) {
    return this.buildSelectOneSQL(null, Collections.singletonMap("id", id));
  }

  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.COUNT_BY_MAP)
  long buildCountSQL(ProviderContext context, final Map<String, Object> whereConditions);

  default long countByMap(final Map<String, Object> whereConditions) {
    return this.buildCountSQL(
        null, whereConditions == null ? Collections.emptyMap() : whereConditions);
  }

  default long countAll() {
    return this.buildCountSQL(null, Collections.emptyMap());
  }

  @UpdateProvider(type = MybatisCommand.class, method = MybatisCommand.UPDATE_MAP_BY_MAP)
  void buildUpdateSQL(
      ProviderContext context,
      final Map<String, Object> updateMap,
      final Map<String, Object> whereConditions);

  default void updateMapByMap(
      final Map<String, Object> updateMap, final Map<String, Object> whereConditions) {
    this.buildUpdateSQL(null, updateMap, whereConditions);
  }

  default void updateById(final T entity, final Long id) {
    this.buildUpdateSQL(null, MybatisCommand.toMap(entity), Collections.singletonMap("id", id));
  }

  default void updateByMap(final T entity, final Map<String, Object> whereConditions) {
    this.buildUpdateSQL(
        null,
        MybatisCommand.toMap(entity),
        whereConditions == null ? Collections.emptyMap() : whereConditions);
  }

  default void updateMapById(final Map<String, Object> updateMap, final Long id) {
    this.buildUpdateSQL(null, updateMap, Collections.singletonMap("id", id));
  }

  @DeleteProvider(type = MybatisCommand.class, method = MybatisCommand.DELETE_BY_MAP)
  void buildDeleteSQL(ProviderContext context, final Map<String, Object> whereConditions);

  default void deleteByMap(final Map<String, Object> whereConditions) {
    this.buildDeleteSQL(null, whereConditions == null ? Collections.emptyMap() : whereConditions);
  }

  default void deleteById(final Long id) {
    this.buildDeleteSQL(null, Collections.singletonMap("id", id));
  }
}
