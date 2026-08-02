package src.backend.bus.access;

import src.backend.bus.entity.Bus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 버스 담당자(기사·선탑자) 접근 검증 헬퍼.
 * 역할 애너테이션({@code @PreAuthorize("hasAnyRole('DRIVER','ATTENDANT')")})만으로는
 * "기사인가"만 알 뿐 "이 버스의 담당인가"는 모른다 — drivesession·rideevent·routing
 * 세 모듈이 각자 같은 판정을 갖고 있으면 규칙이 바뀔 때 하나를 놓쳐도 컴파일 에러 없이
 * 권한 우회 구멍이 생긴다. 판정 지점을 여기 하나로 모은다.
 */
public final class BusCrewGuard {

    private BusCrewGuard() {
    }

    /** 기사 또는 선탑자 본인만 그 버스에 관한 데이터를 볼 수 있다. */
    public static void requireAssignedCrew(Bus bus, AuthUser actor) {
        boolean isDriver = bus.getDriver() != null && bus.getDriver().getId().equals(actor.userId());
        boolean isAttendant = bus.getAttendant() != null && bus.getAttendant().getId().equals(actor.userId());
        if (!isDriver && !isAttendant) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사·선탑자만 조회할 수 있습니다");
        }
    }
}
