package io.github.bestheroz.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.InsertProvider;

/**
 * 자동 증가 기본키가 없는 엔티티용 저장소.
 *
 * <p>조회/집계/수정/삭제는 모두 {@link MybatisRepositoryBase} 에 있고, 여기에는 {@link MybatisRepository} 와 실제로 다른
 * 부분만 남는다 -- insert 프로바이더에 {@code @Options(useGeneratedKeys = true, keyProperty = "id")} 를 붙이지 않는
 * 것이다. 키를 돌려받을 자리가 없는 엔티티에 그 옵션을 붙이면 드라이버가 생성 키를 요구하다 실패한다.
 *
 * @param <T> 엔티티 타입
 */
public interface MybatisNoIdRepository<T> extends MybatisRepositoryBase<T> {
  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT)
  void buildInsertSQL(final T entity);

  default void insert(final T entity) {
    this.buildInsertSQL(entity);
  }

  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT_BATCH)
  void buildInsertBatchSQL(final List<T> entities);

  default void insertBatch(final List<T> entities) {
    this.buildInsertBatchSQL(entities);
  }
}
