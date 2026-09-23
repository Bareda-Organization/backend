package src.backend.routing.dto;

import java.util.List;

import jakarta.validation.Valid;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 고정 노선 순서 최적화 요청(RTE-09 · Ruling 180).
 *
 * <p><b>기준점을 요청이 주는 것이 이 계약의 판단 지점이다.</b> 엔진은 출발지·도착지를 반드시
 * 요구하는데({@code RouteOrderInput}), 고정 노선에는 그 두 점을 담을 자리가 부재하다 —
 * {@code ERD route} 에 좌표 컬럼이 없고 {@code academy} 에도 없다. 서버가 정차지 중 하나를 골라
 * 기준점으로 삼는 방식을 쓰지 않은 이유는 그 고름이 <b>보이지 않는 정책</b>이 되기 때문이다:
 * 어느 자리를 골랐는지에 따라 산출 순서가 달라지는데 요청·응답 어디에도 그 값이 남지 않는다.
 *
 * <p>등원은 첫 승차지 이전 기준점 → 학원, 하원은 학원 → 마지막 하차지다(ARCHITECTURE §8.2) —
 * 방향에 맞춘 값을 채우는 것은 호출자의 몫이다.
 *
 * <p><b>2026-09-23 — 둘 다 비우면 서버가 정한다.</b> 위 기각 사유는 학원에 좌표가 없던 시절의 것이다.
 * 지금은 학원 좌표가 있고, 서버는 확정 배치와 <b>같은 규칙</b>(Ruling 190 — 등원은 첫 승차지 → 학원,
 * 하원은 학원 → 마지막 하차지)으로 정한다. 규칙이 한 줄로 공개돼 있어 요청과 편성 상태만으로 기준점을
 * 다시 구할 수 있으므로 "보이지 않는 정책" 이 아니다. 관계자 화면의 위경도 입력칸은 이것으로 없앴다.
 *
 * <p>{@code fixedStopIds} — 이 승하차지들은 <b>지금 순번</b>을 지키고 나머지만 다시 매긴다(시점·종점·특정 순서 고정).
 */
public record RouteOptimizeRequest(
        @Valid GeoPointRequest origin,
        @Valid GeoPointRequest destination,
        List<Long> fixedStopIds) {

    /** 자리를 지킬 승하차지(2026-09-23 사용자 지시) — 비우면 전부 다시 매긴다. */
    public List<Long> fixedStopIdsOrEmpty() {
        return fixedStopIds == null ? List.of() : fixedStopIds;
    }

    /**
     * 기준점을 요청이 정했는가 — 둘 다 없으면 서버가 방향 규칙으로 정한다(2026-09-23, 아래 참고).
     *
     * <p>하나만 준 요청은 거부한다. 한쪽은 호출자, 한쪽은 규칙이 정한 산출은 요청만 보고는 어느 조건에서
     * 나왔는지 재현할 수 없다.
     */
    public boolean givesAnchors() {
        if ((origin == null) != (destination == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "출발·도착 기준점은 둘 다 주거나 둘 다 비운다");
        }
        return origin != null;
    }
}
