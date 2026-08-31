package src.backend.student.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
 */
public record StudentRouteResponse(Long runId, String busNo, OffsetDateTime departTime, boolean confirmed,
        Contact driver, EscortContact escort, Long myStopId, List<Stop> stops) {

    public record Contact(String name) {
    }

    public record EscortContact(String name, String phone) {
    }

    /** {@code change} 는 {@code added}·{@code skipped} 만 온다 — {@code removed} 는 정본에 없다. */
    public record Stop(Long stopId, int seq, String name, String address, BigDecimal lat, BigDecimal lng,
            String change) {
    }
}
