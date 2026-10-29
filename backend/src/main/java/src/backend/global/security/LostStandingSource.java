package src.backend.global.security;

import java.util.Collection;
import java.util.List;

/** {@link AccountStanding} 이 읽는 계정 상태의 출처 — 계정 모듈의 저장소가 구현한다. */
public interface LostStandingSource {

    /** 주어진 계정 중 권한을 잃은(차단 · 퇴사한 관계자 · 삭제된 매니저) 계정의 id. 행이 없는 계정은 결과에 없다. */
    List<Long> findIdsWithLostStanding(Collection<Long> ids);
}
