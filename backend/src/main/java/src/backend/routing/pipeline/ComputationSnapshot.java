package src.backend.routing.pipeline;

import java.util.Map;
import java.util.Objects;

import src.backend.routing.entity.RouteVersionSource;

/**
 * 이번 산출이 어떤 조건에서 나왔는지 — {@code route_version} 의 네 컬럼에 1:1 대응한다
 * (TECH_DECISIONS §8.5.1).
 *
 * <p>산출물만 남기고 <b>산출 조건</b>을 안 남기면 "왜 이 순서로 돌았나" 를 나중에 재현할 수단이
 * 부재하다. 정책은 바뀌는데 과거 노선은 남으므로, 값을 참조로 두지 않고 그 시점의 값을 그대로 굳힌다.
 *
 * @param engineName     어느 순서 최적화 전략의 산출물인지 — {@code engine_name varchar(30)}
 * @param policySnapshot 그 시점의 정책값 — {@code policy_snapshot jsonb}
 * @param trigger        누가 촉발했는지 — {@code source varchar(20)}
 * @param fallbackUsed   지도 API 폴백(직선거리 근사)으로 계산됐는지 — {@code fallback_used}
 */
public record ComputationSnapshot(
        String engineName,
        Map<String, Object> policySnapshot,
        RouteVersionSource trigger,
        boolean fallbackUsed) {

    /** {@code route_version.engine_name} 의 컬럼 폭. */
    private static final int ENGINE_NAME_MAX_LENGTH = 30;

    /**
     * 컬럼 폭을 여기서 막는 이유는 {@code varchar(30)} 이 DB 에만 있으면 이름이 긴 전략을 새로
     * 끼웠을 때 <b>계산은 전부 끝난 뒤 저장(Phase 7)에서만</b> 터지기 때문이다 — 그 시점에는 이미
     * 외부 지도 API 호출까지 마친 상태라 회차 하나가 통째로 버려진다.
     */
    public ComputationSnapshot {
        Objects.requireNonNull(engineName, "엔진 이름이 없으면 어느 전략의 산출물인지 가릴 수 없다");
        if (engineName.isBlank() || engineName.length() > ENGINE_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "엔진 이름은 1~" + ENGINE_NAME_MAX_LENGTH + "자다: " + engineName);
        }
        Objects.requireNonNull(trigger, "촉발 원인이 없으면 route_version.source 를 채울 수 없다");
        policySnapshot = Map.copyOf(Objects.requireNonNull(policySnapshot, "정책 스냅샷이 없다"));
    }
}
