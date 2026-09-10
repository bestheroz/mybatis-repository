package io.github.bestheroz.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Options;

/**
 * 자동 증가 기본키를 쓰는 엔티티용 저장소.
 *
 * <p>조회/집계/수정/삭제는 모두 {@link MybatisRepositoryBase} 에 있고, 여기에는 {@link MybatisNoIdRepository} 와 실제로
 * 다른 부분만 남는다 -- insert 프로바이더 두 개에 붙는 {@code @Options(useGeneratedKeys = true, keyProperty = "id")}
 * 다. 이것이 있어야 insert 후 엔티티의 {@code id} 필드에 생성된 키가 채워진다.
 *
 * @param <T> 엔티티 타입
 */
public interface MybatisRepository<T> extends MybatisRepositoryBase<T> {
  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void buildInsertSQL(final T entity);

  default void insert(final T entity) {
    this.buildInsertSQL(entity);
  }

  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT_BATCH)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void buildInsertBatchSQL(final List<T> entities);

  default void insertBatch(final List<T> entities) {
    this.buildInsertBatchSQL(entities);
  }
}
