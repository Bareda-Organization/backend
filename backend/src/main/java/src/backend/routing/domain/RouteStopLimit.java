package src.backend.routing.domain;

import java.util.Collection;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 노선 하나에 담을 수 있는 정차지 수의 상한(R46 · Ruling 613) — 상한이 없으면 정차지 500개짜리 노선 하나를 열 때마다 외부
 * 경로 호출이 구간 수만큼 나가 일일 한도를 갉아먹고 요청 스레드를 수 분 묶는다. 요청 DTO 가 같은 상수로 {@code 422} 를 내고,
 * 서비스는 DTO 를 거치지 않는 호출에 같은 검증을 건다.
 */
public final class RouteStopLimit {

    /** 한 노선의 정차지 상한 — 정상 노선은 30개 안쪽이다. */
    public static final int MAX_STOPS = 50;

    /** 거절 사유 문구 — DTO 의 {@code @Size} 와 서비스 검증이 같은 말을 쓴다. */
    public static final String MESSAGE = "정차지는 한 노선에 최대 " + MAX_STOPS + "개까지 담을 수 있습니다";

    private RouteStopLimit() {
    }

    /** 정차지가 상한을 넘으면 {@code 422 VALIDATION_FAILED} — 목록이 비어 있거나 부재하면 통과한다. */
    public static void assertWithin(Collection<?> stops) {
        if (stops != null && stops.size() > MAX_STOPS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MESSAGE);
        }
    }
}
