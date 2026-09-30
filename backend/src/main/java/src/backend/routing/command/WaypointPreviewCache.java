package src.backend.routing.command;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import src.backend.routing.pipeline.RouteComputation;

/**
 * 경유 지점 미리보기(재최적화 결과) 보관소 — 배포({@code apply=true})가 관리자가 본 계산을 그대로 쓰게 한다
 * (API_SPEC §5.15 {@code preview_token} · ARCHITECTURE §8.4 "계산 결과는 요청 단위로 캐시", BR-051).
 *
 * <p><b>회차당 1건</b>이다 — 새 미리보기가 앞 미리보기를 덮어 앞 토큰은 {@code 409 PREVIEW_STALE} 이 된다.
 * 추가 미리보기가 이미 회차의 미배포 경유 지점 행을 하나만 남기는 것({@link WaypointStore#saveCandidate})과 같은
 * 규칙이고, 크기가 회차 수로 묶인다.
 *
 * <p>프로세스 메모리에 둔다 — 백엔드 인스턴스가 1개라는 배포 전제({@code InMemoryApprovalPreviewCache} 와 같은 근거).
 * 배포하지 않은 미리보기는 그 회차의 운행일이 지나도 남으므로, 일일 회차 생성 배치가 날짜가 바뀔 때
 * {@link #evictBefore} 로 지난 날짜 회차의 것을 지운다(R36-BE).
 * 다중 인스턴스가 되면 승인 미리보기 캐시와 함께 Redis 로 옮긴다.
 */
@Component
public class WaypointPreviewCache {

    private final Map<Long, Entry> store = new ConcurrentHashMap<>();

    /** 이 회차에 지금 유효한 미리보기. */
    public Optional<WaypointPreview> find(Long runId) {
        return Optional.ofNullable(store.get(runId)).map(Entry::preview);
    }

    /** 미리보기를 보관한다 — {@code serviceDate} 는 그 회차의 운행일이고, 지난 날짜 정리({@link #evictBefore})의 기준이다. */
    public void put(Long runId, LocalDate serviceDate, WaypointPreview preview) {
        store.put(runId, new Entry(serviceDate, preview));
    }

    /** 배포한 미리보기를 지운다 — 같은 토큰으로 두 번 배포되지 않게 한다. */
    public void evict(Long runId) {
        store.remove(runId);
    }

    /** 운행일이 {@code today} 보다 앞선 회차의 미리보기를 지운다 — 지난 회차는 배포할 수 없어 남겨 둘 이유가 없다. */
    public void evictBefore(LocalDate today) {
        store.values().removeIf(entry -> entry.serviceDate().isBefore(today));
    }

    private record Entry(LocalDate serviceDate, WaypointPreview preview) {
    }

    /**
     * 미리보기 한 건.
     *
     * @param seq            추가면 새 경유 지점이 설 자리, 제거면 0 — 배포 때 지문을 다시 뽑는 재료다
     * @param baseVersionId  계산의 바탕이 된 노선 판본 — 배포가 그 사이 다른 배포가 끼었는지 확인한다(BR-021)
     */
    public record WaypointPreview(String token, long waypointId, boolean removal, int seq, String fingerprint,
            Long baseVersionId, RouteComputation computation) {
    }
}
