package src.backend.location.websocket;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 학생별 WebSocket 연결 상태를 메모리에 추적한다(위치 좌표 자체와 같은 성격의 휘발성 데이터라
 * {@code LocationRepository}처럼 in-memory로 충분 — 재시작 시 초기화돼도 무방).
 * "연결 중"인 학생은 맵에 없고, 끊긴 학생만 끊긴 시각과 함께 들어있다.
 */
@Component
public class LocationSessionRegistry {

    private final Map<Long, LocalDateTime> disconnectedAt = new ConcurrentHashMap<>();

    /** 연결(혹은 재연결) — 더는 끊긴 상태가 아니므로 제거한다. */
    public void markConnected(Long studentId) {
        disconnectedAt.remove(studentId);
    }

    /** 연결 해제 — 이미 끊긴 상태로 기록돼 있으면 최초 시각을 유지한다(중복 이벤트 방어). */
    public void markDisconnected(Long studentId) {
        disconnectedAt.putIfAbsent(studentId, LocalDateTime.now());
    }

    /** 현재 끊긴 상태인 학생과 끊긴 시각의 스냅샷 — 스케줄러가 이 값으로 유예시간을 판정한다. */
    public Map<Long, LocalDateTime> snapshotDisconnected() {
        return Map.copyOf(disconnectedAt);
    }
}
