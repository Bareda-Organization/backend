package src.backend.student.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.dto.ChildListResponse;

/**
 * 학부모 앱의 자녀 목록 조회(ATT-03, API_SPEC §3.1).
 *
 * <p>범위 판정을 자기가 하지 않고 {@link GuardianChildAccess} 에 맡긴다 — "연결된 자녀만" 이라는
 * 규칙이 조회 서비스마다 복제되면 다음에 생기는 학부모 조회가 그것을 빠뜨린 채 태어난다(§1.5).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChildQueryService {

    private final GuardianChildAccess guardianChildAccess;

    /** 연결된 자녀 전부 — 없으면 빈 {@code items[]} 이고 오류가 아니다(§3.1). */
    public ChildListResponse list(AuthUser requester) {
        return ChildListResponse.from(
                guardianChildAccess.linkedChildren(guardianChildAccess.requireGuardian(requester)));
    }
}
