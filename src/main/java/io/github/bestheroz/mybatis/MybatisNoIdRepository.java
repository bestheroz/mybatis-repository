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
