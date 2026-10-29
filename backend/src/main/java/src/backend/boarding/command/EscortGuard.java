package src.backend.boarding.command;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * C-06 — 동승자 전용 호출의 역할 검사. 회차 · 탑승자 조회보다 먼저 걸어야 다른 학원 자원의 존재 여부를 흘리지 않는다.
 */
public final class EscortGuard {

    private EscortGuard() {
    }

    /** 요청자가 동승자가 아니면 {@code 403 ESCORT_ONLY} 로 끝낸다. */
    public static void require(AuthUser requester) {
        if (requester.role() != Role.ESCORT) {
            throw new BusinessException(ErrorCode.ESCORT_ONLY);
        }
    }
}
