package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisRepositoryTest {

  private TestRepository repository;

  private TestEntity testEntity1;
  private TestEntity testEntity2;
  private List<TestEntity> testEntities;

  @BeforeEach
  void setUp() {
    // given
    testEntity1 = new TestEntity(1L, "item1");
    testEntity2 = new TestEntity(2L, "item2");
    testEntities = Arrays.asList(testEntity1, testEntity2);

    // CALLS_REAL_METHODS 옵션을 사용하여 default 메소드를 실제로 호출하도록 설정
    repository = mock(TestRepository.class, CALLS_REAL_METHODS);
  }

  @Nested
  @DisplayName("count 테스트")
  class countTest {
    @Test
    @DisplayName("전체 아이템 수를 조회할 수 있다")
    void countAll_ShouldReturnTotalCount() {
      // given
      long expectedCount = 2L;
      doReturn(expectedCount).when(repository).countByMap(eq(Collections.emptyMap()));

      // when
      long actualCount = repository.countAll();

      // then
      assertThat(actualCount).isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("아이템 수를 카운트할 수 있다")
    void countByMap_ShouldReturnCount() {
      // given
      Map<String, Object> whereConditions = new HashMap<>();
      whereConditions.put("name", "item1");
      long expectedCount = 1L;

      doReturn(expectedCount).when(repository).countByMap(eq(whereConditions));

      // when
      long actualCount = repository.countByMap(whereConditions);

      // then
      assertThat(actualCount).isEqualTo(expectedCount);
    }
  }

  @Nested
  @DisplayName("getItem 테스트")
  class getItemTest {
    @Test
    @DisplayName("ID로 단일 아이템을 조회할 수 있다")
    void getItemById_ShouldReturnSingleItem() {
      // given
      Long id = 1L;
      Map<String, Object> whereConditions = Collections.singletonMap("id", id);

      doReturn(Optional.of(testEntity1)).when(repository).getItemByMap(eq(whereConditions));

      // when
      Optional<TestEntity> actualItem = repository.getItemById(id);

      // then
      assertThat(actualItem)
          .isPresent()
          .hasValueSatisfying(
              item -> {
                assertThat(item.getId()).isEqualTo(id);
                assertThat(item.getName()).isEqualTo("item1");
              });
    }
  }

  @Nested
  @DisplayName("getItems 테스트")
  class getItemsTest {
    private Map<String, Object> whereConditions;
    private List<String> orderByConditions;
    private Integer limit;
    private Integer offset;

    @BeforeEach
    void setUp() {
      // given
      whereConditions = Collections.singletonMap("name", "item1");
      orderByConditions = Collections.singletonList("name ASC");
      limit = 10;
      offset = 0;
    }

    @Test
    @DisplayName("전체 아이템을 조회할 수 있다")
    void getItems_ShouldReturnAllItems() {
      // given
      doReturn(testEntities)
          .when(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              eq(Collections.emptySet()),
              eq(Collections.emptySet()),
              eq(Collections.emptyMap()),
              eq(Collections.emptyList()),
              eq(null),
              eq(null));

      // when
      List<TestEntity> actualItems = repository.getItems();

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("페이징 처리된 아이템을 조회할 수 있다")
    void getItemsLimitOffset_ShouldReturnPaginatedItems() {
      // given
      doReturn(testEntities)
          .when(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              eq(Collections.emptySet()),
              eq(Collections.emptySet()),
              eq(Collections.emptyMap()),
              eq(Collections.emptyList()),
              eq(limit),
              eq(offset));

      // when
      List<TestEntity> actualItems = repository.getItemsLimitOffset(limit, offset);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("정렬 조건으로 아이템을 조회할 수 있다")
    void getItemsOrderBy_ShouldReturnOrderedItems() {
      // given
      doReturn(testEntities)
          .when(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              eq(Collections.emptySet()),
              eq(Collections.emptySet()),
              eq(Collections.emptyMap()),
              eq(orderByConditions),
              eq(null),
              eq(null));

      // when
      List<TestEntity> actualItems = repository.getItemsOrderBy(orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).extracting("name").containsExactly("item1", "item2");
    }

    @Test
    @DisplayName("정렬 조건과 페이징으로 아이템을 조회할 수 있다")
    void getItemsOrderByLimitOffset_ShouldReturnOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              Collections.emptySet(),
              Collections.emptyMap(),
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getItemsOrderByLimitOffset(orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("조건에 따라 아이템을 조회할 수 있다")
    void getItemsByMap_ShouldReturnFilteredItems() {
      // given
      List<TestEntity> testEntities = Collections.singletonList(testEntity1);

      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              Collections.emptySet(),
              whereConditions,
              Collections.emptyList(),
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems = repository.getItemsByMap(whereConditions);

      // then
      assertThat(actualItems).hasSize(1).extracting("name").containsExactly("item1");
    }

    @Test
    @DisplayName("조건과 페이징으로 아이템을 조회할 수 있다")
    void getItemsByMapLimitOffset_ShouldReturnFilteredAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              Collections.emptySet(),
              whereConditions,
              Collections.emptyList(),
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getItemsByMapLimitOffset(whereConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("조건과 정렬로 아이템을 조회할 수 있다")
    void getItemsByMapOrderBy_ShouldReturnOrderedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getItemsByMapOrderBy(whereConditions, orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("조건, 정렬, 페이징으로 아이템을 조회할 수 있다")
    void getItemsByMapOrderByLimitOffset_ShouldReturnOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getItemsByMapOrderByLimitOffset(
              whereConditions, orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }
  }

  @Nested
  @DisplayName("getDistinctItems 테스트")
  class DistinctQueryTest {
    private Set<String> distinctColumns;
    private Map<String, Object> whereConditions;
    private List<String> orderByConditions;
    private Integer limit;
    private Integer offset;

    @BeforeEach
    void setUp() {
      // given
      distinctColumns = new HashSet<>(Collections.singletonList("name"));
      whereConditions = Collections.singletonMap("name", "item2");
      orderByConditions = Collections.singletonList("name ASC");
      limit = 10;
      offset = 0;
    }

    @Test
    @DisplayName("Distinct 컬럼으로 아이템을 조회할 수 있다")
    void getDistinctItems_ShouldReturnDistinctItems() {
      // given
      doReturn(testEntities)
          .when(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              eq(distinctColumns),
              eq(Collections.emptySet()),
              eq(Collections.emptyMap()),
              eq(Collections.emptyList()),
              eq(null),
              eq(null));

      // when
      List<TestEntity> actualItems = repository.getDistinctItems(distinctColumns);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("Distinct 조건과 페이징으로 아이템을 조회할 수 있다")
    void getDistinctItemsLimitOffset_ShouldReturnDistinctAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              Collections.emptyMap(),
              Collections.emptyList(),
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsLimitOffset(distinctColumns, limit, offset);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("Distinct 컬럼과 정렬로 아이템을 조회할 수 있다")
    void getDistinctItemsOrderBy_ShouldReturnDistinctAndOrderedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              Collections.emptyMap(),
              orderByConditions,
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsOrderBy(distinctColumns, orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("Distinct 컬럼으로 정렬 및 페이징 처리된 아이템을 조회할 수 있다")
    void getDistinctItemsOrderByLimitOffset_ShouldReturnOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              Collections.emptyMap(),
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsOrderByLimitOffset(
              distinctColumns, orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              Collections.emptyMap(),
              orderByConditions,
              limit,
              offset);
    }

    @Test
    @DisplayName("Distinct 컬럼과 조건으로 아이템을 조회할 수 있다")
    void getDistinctItemsByMap_ShouldReturnDistinctAndFilteredItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              Collections.emptyList(),
              null,
              null))
          .thenReturn(Collections.singletonList(testEntity1));

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsByMap(distinctColumns, whereConditions);

      // then
      assertThat(actualItems).hasSize(1).containsExactly(testEntity1);
    }

    @Test
    @DisplayName("Distinct 컬럼과 조건으로 페이징 처리된 아이템을 조회할 수 있다")
    void getDistinctItemsByMapLimitOffset_ShouldReturnPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              Collections.emptyList(),
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsByMapLimitOffset(
              distinctColumns, whereConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("Distinct 컬럼으로 조건 및 정렬된 아이템을 조회할 수 있다")
    void getDistinctItemsByMapOrderBy_ShouldReturnFilteredAndOrderedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsByMapOrderBy(
              distinctColumns, whereConditions, orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              null,
              null);
    }

    @Test
    @DisplayName("Distinct 컬럼으로 조건, 정렬 및 페이징 처리된 아이템을 조회할 수 있다")
    void getDistinctItemsByMapOrderByLimitOffset_ShouldReturnFilteredOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getDistinctItemsByMapOrderByLimitOffset(
              distinctColumns, whereConditions, orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              distinctColumns,
              Collections.emptySet(),
              whereConditions,
              orderByConditions,
              limit,
              offset);
    }
  }

  @Nested
  @DisplayName("getTargetItems 테스트")
  class TargetColumnTest {
    private Set<String> targetColumns;
    private Map<String, Object> whereConditions;
    private List<String> orderByConditions;
    private Integer limit;
    private Integer offset;

    @BeforeEach
    void setUp() {
      // given
      targetColumns = new HashSet<>(Collections.singletonList("name"));
      whereConditions = Collections.singletonMap("name", "item2");
      orderByConditions = Collections.singletonList("name ASC");
      limit = 10;
      offset = 0;
    }

    @Test
    @DisplayName("Target 컬럼으로 아이템을 조회할 수 있다")
    void getTargetItems_ShouldReturnTargetItems() {
      // given
      doReturn(testEntities)
          .when(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              eq(Collections.emptySet()),
              eq(targetColumns),
              eq(Collections.emptyMap()),
              eq(Collections.emptyList()),
              eq(null),
              eq(null));

      // when
      List<TestEntity> actualItems = repository.getTargetItems(targetColumns);

      // then
      assertThat(actualItems).hasSize(2);
    }

    @Test
    @DisplayName("Target 컬럼과 페이징으로 아이템을 조회할 수 있다")
    void getTargetItemsLimitOffset_ShouldReturnPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              Collections.emptyMap(),
              Collections.emptyList(),
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsLimitOffset(targetColumns, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("Target 컬럼과 정렬로 아이템을 조회할 수 있다")
    void getTargetItemsOrderBy_ShouldReturnOrderedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              Collections.emptyMap(),
              orderByConditions,
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsOrderBy(targetColumns, orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("Target 컬럼, 정렬, 페이징으로 아이템을 조회할 수 있다")
    void getTargetItemsOrderByLimitOffset_ShouldReturnOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              Collections.emptyMap(),
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsOrderByLimitOffset(
              targetColumns, orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);
    }

    @Test
    @DisplayName("Target 컬럼과 조건으로 아이템을 조회할 수 있다")
    void getTargetItemsByMap_ShouldReturnFilteredItems() {
      // given
      List<TestEntity> testEntities = Collections.singletonList(testEntity1);

      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              Collections.emptyList(),
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems = repository.getTargetItemsByMap(targetColumns, whereConditions);

      // then
      assertThat(actualItems).hasSize(1).extracting("name").containsExactly("item1");
    }

    @Test
    @DisplayName("Target 컬럼으로 조건 및 페이징 처리된 아이템을 조회할 수 있다")
    void getTargetItemsByMapLimitOffset_ShouldReturnFilteredAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              Collections.emptyList(),
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsByMapLimitOffset(targetColumns, whereConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              Collections.emptyList(),
              limit,
              offset);
    }

    @Test
    @DisplayName("Target 컬럼으로 조건 및 정렬된 아이템을 조회할 수 있다")
    void getTargetItemsByMapOrderBy_ShouldReturnFilteredAndOrderedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              orderByConditions,
              null,
              null))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsByMapOrderBy(targetColumns, whereConditions, orderByConditions);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              orderByConditions,
              null,
              null);
    }

    @Test
    @DisplayName("Target 컬럼으로 조건, 정렬 및 페이징 처리된 아이템을 조회할 수 있다")
    void getTargetItemsByMapOrderByLimitOffset_ShouldReturnFilteredOrderedAndPaginatedItems() {
      // given
      when(repository.getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              orderByConditions,
              limit,
              offset))
          .thenReturn(testEntities);

      // when
      List<TestEntity> actualItems =
          repository.getTargetItemsByMapOrderByLimitOffset(
              targetColumns, whereConditions, orderByConditions, limit, offset);

      // then
      assertThat(actualItems).hasSize(2).containsExactly(testEntity1, testEntity2);

      verify(repository)
          .getDistinctAndTargetItemsByMapOrderByLimitOffset(
              Collections.emptySet(),
              targetColumns,
              whereConditions,
              orderByConditions,
              limit,
              offset);
    }

    @Nested
    @DisplayName("Insert 테스트")
    class InsertTest {
      @Test
      @DisplayName("아이템을 생성할 수 있다")
      void insert_ShouldCreateItem() {
        // given
        TestEntity newEntity = new TestEntity(null, "newItem");
        doNothing().when(repository).insert(any(TestEntity.class));

        // when
        repository.insert(newEntity);
        newEntity.setId(1L);

        // then
        assertThat(newEntity.getId()).isNotNull();
      }

      @Test
      @DisplayName("여러 아이템을 일괄 생성할 수 있다")
      void insertBatch_ShouldCreateMultipleItems() {
        // given
        List<TestEntity> entities =
            Arrays.asList(new TestEntity(null, "item1"), new TestEntity(null, "item2"));

        doNothing().when(repository).insertBatch(entities);

        // when
        repository.insertBatch(entities);

        // then
        verify(repository).insertBatch(eq(entities));
      }
    }

    @Nested
    @DisplayName("Update 테스트")
    class UpdateTest {
      @Test
      @DisplayName("ID로 아이템을 수정할 수 있다")
      void updateById_ShouldUpdateItem() {
        // given
        TestEntity entity = new TestEntity(1L, "updatedItem");
        Long id = 1L;

        // when
        repository.updateById(entity, id);

        // then
        verify(repository).updateMapByMap(any(), eq(Collections.singletonMap("id", id)));
      }

      @Test
      @DisplayName("조건으로 아이템을 수정할 수 있다")
      void updateByMap_ShouldUpdateItems() {
        // given
        TestEntity entity = new TestEntity(1L, "updatedItem");
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("name", "item1");

        // when
        repository.updateByMap(entity, whereConditions);

        // then
        verify(repository).updateMapByMap(any(), eq(whereConditions));
      }

      @Test
      @DisplayName("Map으로 아이템을 수정할 수 있다")
      void updateMapById_ShouldUpdateItem() {
        // given
        Long id = 1L;
        Map<String, Object> updateMap = new HashMap<>();
        updateMap.put("name", "updatedItem");

        doNothing()
            .when(repository)
            .updateMapByMap(eq(updateMap), eq(Collections.singletonMap("id", id)));

        // when
        repository.updateMapById(updateMap, id);

        // then
        verify(repository).updateMapByMap(eq(updateMap), eq(Collections.singletonMap("id", id)));
      }
    }

    @Nested
    @DisplayName("Delete 테스트")
    class DeleteTest {
      @Test
      @DisplayName("ID로 아이템을 삭제할 수 있다")
      void deleteById_ShouldDeleteItem() {
        // given
        Long id = 1L;

        // when
        repository.deleteById(id);

        // then
        verify(repository).deleteByMap(eq(Collections.singletonMap("id", id)));
      }

      @Test
      @DisplayName("조건에 따라 아이템을 삭제할 수 있다")
      void deleteByMap_ShouldDeleteItems() {
        // given
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("name", "item1");

        doNothing().when(repository).deleteByMap(whereConditions);

        // when
        repository.deleteByMap(whereConditions);

        // then
        verify(repository).deleteByMap(whereConditions);
      }
    }
  }

  static class TestEntity {
    private Long id;
    private String name;

    public TestEntity(Long id, String name) {
      this.id = id;
      this.name = name;
    }

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  interface TestRepository extends MybatisRepository<TestEntity> {}
}
