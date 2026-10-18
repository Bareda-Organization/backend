package src.backend.routing.command;

import java.time.LocalDate;
import java.util.Optional;

import src.backend.routing.pipeline.RouteComputation;

/**
 * 경유 지점 미리보기(재최적화 결과) 보관소 — 배포({@code apply=true})가 관리자가 본 계산을 그대로 쓰게 한다
 * (API_SPEC §5.15 {@code preview_token} · ARCHITECTURE §8.4 "계산 결과는 요청 단위로 캐시", BR-051).
 *
 * <p><b>회차당 1건</b>이다 — 새 미리보기가 앞 미리보기를 덮어 앞 토큰은 {@code 409 PREVIEW_STALE} 이 된다.
 * 추가 미리보기가 이미 회차의 미배포 경유 지점 행을 하나만 남기는 것({@link WaypointStore#saveCandidate})과 같은
 * 규칙이고, 크기가 회차 수로 묶인다.
 *
 * <p>구현은 {@link RedisWaypointPreviewCache} 하나다 — 미리보기를 만든 인스턴스와 배포를 처리하는 인스턴스가 다를 수 있어(Ruling 900 C) 메모리가 아니라 Redis 에 둔다.
 * 보관소(Redis)를 못 쓰는 동안은 이 인스턴스 안의 기억이 대신 들고, 거기에도 없으면 없는 것으로 본다 — 배포는 기존 {@code 409 PREVIEW_STALE} 로 답한다.
 */
public interface WaypointPreviewCache {

    /** 이 회차에 지금 유효한 미리보기 — 없으면 빈 값(보관소 오류는 이 인스턴스 안의 기억을 본다). */
    Optional<WaypointPreview> find(Long runId);

    /** 미리보기를 보관한다 — {@code serviceDate} 는 그 회차의 운행일이고, 운행일이 끝나면 만료된다(지난 회차는 배포할 수 없어 남겨 둘 이유가 없다). */
    void put(Long runId, LocalDate serviceDate, WaypointPreview preview);

    /** 배포한 미리보기를 지운다 — 같은 토큰으로 두 번 배포되지 않게 한다. */
    void evict(Long runId);

    /**
     * 미리보기 한 건.
     *
     * @param seq            추가면 새 경유 지점이 설 자리, 제거면 0 — 배포 때 지문을 다시 뽑는 재료다
     * @param baseVersionId  계산의 바탕이 된 노선 판본 — 배포가 그 사이 다른 배포가 끼었는지 확인한다(BR-021)
     */
    record WaypointPreview(String token, long waypointId, boolean removal, int seq, String fingerprint,
            Long baseVersionId, RouteComputation computation) {
    }
}
