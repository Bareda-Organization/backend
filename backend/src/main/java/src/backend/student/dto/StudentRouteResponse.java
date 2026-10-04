package src.backend.student.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 학부모 앱의 자녀 노선 조회(LOC-03, API_SPEC §3.10) — {@code stops[]} 는 노선 전체가 아니라
 * <b>승차지 이전 2개 · 승차지 · 하차지만</b> 담는다(§3.10 "표시 범위"). {@code eta} · 승하차지별
 * 탑승 인원은 여기 없다 — {@link src.backend.routing.entity.RunStop#getEta()} 의 javadoc이 "관제
 * 전용이며 학부모·학생 응답에는 포함되지 않는다" 고 명시해, 매니저용 {@code RunRouteResponse.RouteStop}
 * 을 그대로 못 쓰고 이 record 를 새로 둔다.
 *
 * <p>{@code confirmed=false} 는 에러가 아니다 — 배차만 되고 아직 확정 전인 회차는 고정 노선을
 * 그대로 보여주고 이 값으로만 "확정 전" 을 알린다(§3.10 "미확정이어도 에러 아님").
 *
 * <p>기사 전화번호는 없다 — {@code driver} 는 이름만, 연락은 동승자({@code escort.phone})로만
 * 한다("학부모→기사 직접 연락은 스코프 제외").
 *
 * <p>{@code roadPath} 는 확정 노선 도로 좌표 중 {@code stops[]} 의 처음~끝 승하차지 구간만 자른 좌표열이다(Ruling 831) —
 * 범위 밖 승하차지의 위치는 싣지 않는다. 확정 전 · 좌표가 빈 옛 버전이면 빈 목록이고 {@code fallbackUsed} 는 그 버전의 값
 * (확정 전은 {@code false})이다.
 */
public record StudentRouteResponse(Long runId, String busNo, OffsetDateTime departTime, boolean confirmed,
        Contact driver, EscortContact escort, Long myStopId, List<Stop> stops, List<GeoPoint> roadPath,
        boolean fallbackUsed) {

    /** 기사 표시용 이름만 — 연락처는 없다(위 클래스 자바독). */
    public record Contact(String name) {
    }

    /** 동승자 이름·연락처 — 학부모가 연락 가능한 유일한 대상이다. */
    public record EscortContact(String name, String phone) {
    }

    /**
     * {@code change} 는 {@code added}·{@code skipped} 만 온다 — {@code removed} 는 정본에 없다. {@code arrivedAt} 은 그 승하차지의
     * 도착 처리 시각으로 지나간 곳에만 있고 아직이면 {@code null} 이다 — 지난 사실이라 ETA 비노출(C-08)과 무관하다(Ruling 824).
     */
    public record Stop(Long stopId, int seq, String name, String address, BigDecimal lat, BigDecimal lng,
            String change, OffsetDateTime arrivedAt) {
    }
}
