package com.github.bestheroz.mybatis;

import org.apache.ibatis.annotations.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MybatisRepository<T> {
  default List<T> getItems() {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getItemsLimitOffset(final Integer limit, final Integer offset) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getItemsOrderBy(final List<String> orderByConditions) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getItemsOrderByLimitOffset(
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  default List<T> getItemsByMap(final Map<String, Object> whereConditions) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getItemsByMapLimitOffset(
          final Map<String, Object> whereConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getItemsByMapOrderBy(
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getItemsByMapOrderByLimitOffset(
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  default List<T> getDistinctItems(final Set<String> distinctColumns) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getDistinctItemsLimitOffset(
          final Set<String> distinctColumns,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getDistinctItemsOrderBy(
          final Set<String> distinctColumns,
          final List<String> orderByConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getDistinctItemsOrderByLimitOffset(
          final Set<String> distinctColumns,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  default List<T> getDistinctItemsByMap(
          final Set<String> distinctColumns,
          final Map<String, Object> whereConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getDistinctItemsByMapLimitOffset(
          final Set<String> distinctColumns,
          final Map<String, Object> whereConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getDistinctItemsByMapOrderBy(
          final Set<String> distinctColumns,
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getDistinctItemsByMapOrderByLimitOffset(
          final Set<String> distinctColumns,
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            distinctColumns == null ? Collections.emptySet() : distinctColumns,
            Collections.emptySet(),
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  default List<T> getTargetItems(final Set<String> targetColumns) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            Collections.emptyMap(),
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getTargetItemsLimitOffset(
          final Set<String> targetColumns,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            Collections.emptyMap(),
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getTargetItemsOrderBy(
          final Set<String> targetColumns,
          final List<String> orderByConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getTargetItemsOrderByLimitOffset(
          final Set<String> targetColumns,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            Collections.emptyMap(),
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  default List<T> getTargetItemsByMap(
          final Set<String> targetColumns,
          final Map<String, Object> whereConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            null,
            null
    );
  }

  default List<T> getTargetItemsByMapLimitOffset(
          final Set<String> targetColumns,
          final Map<String, Object> whereConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            Collections.emptyList(),
            limit,
            offset
    );
  }

  default List<T> getTargetItemsByMapOrderBy(
          final Set<String> targetColumns,
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            null,
            null
    );
  }

  default List<T> getTargetItemsByMapOrderByLimitOffset(
          final Set<String> targetColumns,
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  ) {
    return this.getDistinctAndTargetItemsByMapOrderByLimitOffset(
            Collections.emptySet(),
            targetColumns == null ? Collections.emptySet() : targetColumns,
            whereConditions == null ? Collections.emptyMap() : whereConditions,
            orderByConditions == null ? Collections.emptyList() : orderByConditions,
            limit,
            offset
    );
  }

  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.SELECT_ITEMS)
  List<T> getDistinctAndTargetItemsByMapOrderByLimitOffset(
          final Set<String> distinctColumns,
          final Set<String> targetColumns,
          final Map<String, Object> whereConditions,
          final List<String> orderByConditions,
          final Integer limit,
          final Integer offset
  );

  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.SELECT_ITEM_BY_MAP)
  Optional<T> getItemByMap(final Map<String, Object> whereConditions);

  default Optional<T> getItemById(final Long id) {
    return this.getItemByMap(Collections.singletonMap("id", id));
  }

  default long countAll() {
    return this.countByMap(Collections.emptyMap());
  }

  @SelectProvider(type = MybatisCommand.class, method = MybatisCommand.COUNT_BY_MAP)
  long countByMap(final Map<String, Object> whereConditions);

  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(final T entity);

  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT_BATCH)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertBatch(final List<T> entities);

  default void updateById(final T entity, final Long id) {
    this.updateMapByMap(MybatisCommand.toMap(entity), Collections.singletonMap("id", id));
  }

  default void updateByMap(final T entity, final Map<String, Object> whereConditions) {
    this.updateMapByMap(
            MybatisCommand.toMap(entity),
            whereConditions == null ? Collections.emptyMap() : whereConditions
    );
  }

  @UpdateProvider(type = MybatisCommand.class, method = MybatisCommand.UPDATE_MAP_BY_MAP)
  void updateMapByMap(
          final Map<String, Object> updateMap,
          final Map<String, Object> whereConditions
  );

  default void updateMapById(final Map<String, Object> updateMap, final Long id) {
    this.updateMapByMap(updateMap, Collections.singletonMap("id", id));
  }

  @DeleteProvider(type = MybatisCommand.class, method = MybatisCommand.DELETE_BY_MAP)
  void deleteByMap(final Map<String, Object> whereConditions);

  default void deleteById(final Long id) {
    this.deleteByMap(Collections.singletonMap("id", id));
  }
}
