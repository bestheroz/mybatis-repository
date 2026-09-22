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
  int buildInsertSQL(final T entity);

  /**
   * 엔티티 하나를 넣는다. 값이 {@code null} 인 필드는 {@code DEFAULT} 로 나가 그 컬럼은 스키마의 기본값을 받는다.
   *
   * @return 영향 행 수(JDBC 가 돌려준 값)
   */
  default int insert(final T entity) {
    return this.buildInsertSQL(entity);
  }

  @InsertProvider(type = MybatisCommand.class, method = MybatisCommand.INSERT_BATCH)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int buildInsertBatchSQL(final List<T> entities);

  /**
   * 엔티티 여러 개를 한 문장으로 넣는다.
   *
   * @return 영향 행 수(JDBC 가 돌려준 값)
   */
  default int insertBatch(final List<T> entities) {
    return this.buildInsertBatchSQL(entities);
  }
}
