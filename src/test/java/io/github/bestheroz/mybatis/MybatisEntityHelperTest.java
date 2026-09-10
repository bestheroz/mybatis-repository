package io.github.bestheroz.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serializable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MybatisEntityHelperTest {

  @Test
  @DisplayName("no-arg 생성자로 만들어도 매퍼에서 엔티티 타입을 뽑아야 한다")
  void extractEntityClassFromMapper_ShouldWorkWithNoArgConstructor() {
    // given: 소비자가 자기 인터셉터에서 이 메소드만 쓰려고 만드는 경로다

    // when
    Class<?> entityClass =
        new MybatisEntityHelper().extractEntityClassFromMapper(ProbeRepository.class);

    // then
    assertThat(entityClass).isEqualTo(ProbeEntity.class);
  }

  interface ProbeRepository extends MybatisNoIdRepository<ProbeEntity> {}

  static class ProbeEntity implements Serializable {
    private static final long serialVersionUID = 1L;
  }
}
