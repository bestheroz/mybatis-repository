package io.github.bestheroz.mybatis;

import java.util.Optional;

/**
 * 감사 컬럼({@code createdBy}/{@code updatedBy})에 적을 "지금 작업하는 사람"을 알려주는 SPI.
 *
 * <p>소비자가 이 인터페이스를 구현해 빈으로 등록했을 때에만 {@link MybatisAuditColumnInterceptor} 가 만들어진다. 즉 감사 컬럼 기입은 전적으로
 * 선택 기능이고, 빈이 없으면 이 라이브러리는 예전과 똑같이 동작한다.
 *
 * <p>구현체는 <b>예외를 던지지 않아야 한다.</b> 배치·스케줄러·기동 훅처럼 사용자 세션이 없는 경로에서 쓰기가 일어나는 것은 오류가 아니라 정상이므로, 그때는
 * {@link Optional#empty()} 를 돌려주면 된다. 그 경우 인터셉터는 {@code *_BY} 컬럼을 건드리지 않고 타임스탬프만 채운다 -- 없는 값을 지어내
 * 넣거나 쓰기를 실패시키는 것보다, 그 컬럼만 비워 두는 편이 사실에 가깝다.
 *
 * <p>쓰기 한 번(문장 하나)마다 최대 한 번만 호출되며, 채울 {@code *_BY} 필드가 엔티티에 하나도 없으면 아예 호출되지 않는다.
 */
public interface MybatisAuditorAware {
  /**
   * 현재 감사자 식별자. 세션/인증 정보가 없으면 {@link Optional#empty()}.
   *
   * @return 감사 컬럼에 적을 문자열. {@code null} 을 돌려주지 말 것(빈 {@code Optional} 로 취급하기는 하지만 규약은 아니다).
   */
  Optional<String> getCurrentAuditor();
}
