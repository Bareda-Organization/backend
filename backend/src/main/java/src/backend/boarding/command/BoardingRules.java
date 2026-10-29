package src.backend.boarding.command;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.entity.VerifyMethod;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 승하차 처리 요청의 값 해석(요청 문자열 → 열거형)과 상태 전이 허용 규칙 모음. */
final class BoardingRules {

    private BoardingRules() {
    }

    /** {@code boarded} · {@code no_show} · {@code alighted} 셋만 허용한다(API_SPEC §4.6 요청 표) — 그 외는 422. */
    static RiderStatus parseTargetStatus(String status) {
        return switch (status) {
            case "boarded" -> RiderStatus.BOARDED;
            case "alighted" -> RiderStatus.ALIGHTED;
            case "no_show" -> RiderStatus.NO_SHOW;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** 확인 수단 문자열을 해석한다 — {@code photo} · {@code manual} 외는 422. */
    static VerifyMethod parseVerifyMethod(String verifyMethod) {
        return switch (verifyMethod) {
            case "photo" -> VerifyMethod.PHOTO;
            case "manual" -> VerifyMethod.MANUAL;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** 응답 · 이벤트에 싣는 소문자 상태 이름. */
    static String statusName(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /**
     * FEATURE_SPEC §3.3 전이 표와 등하원 비대칭(C-07 · BRD-01·02)을 강제한다(Ruling 345, BR-031 1번 갈래) —
     * 허용은 {@code waiting→boarded}(<b>등원</b>) · {@code waiting→no_show} · {@code boarded→alighted}(<b>하원</b>)
     * 셋뿐이다. 승차 처리는 등원 승하차지에서만, 하차 처리는 하원 승하차지에서만 받는다 — 등원의 하차·하원 시작 때 전원 승차된
     * 학생의 승차는 서버 자동 처리뿐이다. 하원의 {@code waiting→no_show} 는 종료 보류 회차에서 되돌리기 뒤에 쓰는 길(BR-031)이라
     * 방향 제한 밖이다. 같은 상태 재요청을 포함해 그 밖은 전부 {@code 409 RIDER_TRANSITION_NOT_ALLOWED} — 표 밖으로 가려면
     * 되돌리기(§4.7)가 먼저다. 이 검사는 <b>PATCH 경로에만</b> 걸린다 — 되돌리기(revertTo)·하원 자동 승차(autoBoard)·미등원
     * (markAbsent)·이동(markRemoved)은 이 메서드를 거치지 않는다.
     */
    static void applyTransition(RunRider rider, Direction direction, RiderStatus fromStatus,
            RiderStatus targetStatus, OffsetDateTime now) {
        boolean allowed = (fromStatus == RiderStatus.WAITING && targetStatus == RiderStatus.BOARDED
                    && direction == Direction.TO_ACADEMY)
                || (fromStatus == RiderStatus.WAITING && targetStatus == RiderStatus.NO_SHOW)
                || (fromStatus == RiderStatus.BOARDED && targetStatus == RiderStatus.ALIGHTED
                    && direction == Direction.FROM_ACADEMY);
        if (!allowed) {
            throw new BusinessException(ErrorCode.RIDER_TRANSITION_NOT_ALLOWED);
        }
        switch (targetStatus) {
            case BOARDED -> rider.board(now);
            case ALIGHTED -> rider.alight(now);
            case NO_SHOW -> rider.markNoShow(now);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
