package src.backend.bus.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import src.backend.bus.entity.Bus;

/**
 * 차량 응답(API_SPEC §5.12) — 목록·등록·수정이 함께 쓴다. {@code route_count}·{@code schedule_count}·{@code today_runs[]} 는
 * 목록 항목에만 싣는다(Ruling 816). 관계자 웹 전용이라 역할별로 가르지 않는다
 * (차량은 학부모·매니저 앱 응답에 실리지 않는다).
 *
 * @param studentCapacity 서버가 계산한 학생 탑승 가능 인원. 요청이 이 값을 실어 보내도 무시된다(§5.12)
 * @param warnings        수정 응답 전용 — 정원 축소 경고(BR-116). 목록·등록 응답에는 싣지 않는다({@code null} 이면 생략)
 */
public record BusResponse(Long id, String busNo, String plateNo, int capacity, int studentCapacity,
        boolean operable, @JsonInclude(JsonInclude.Include.NON_NULL) List<BusWarning> warnings,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer routeCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer scheduleCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<TodayRun> todayRuns) {

    /** 오늘 회차 1건(§5.12 {@code today_runs[]}) — 취소되지 않은 회차만, 출발 순. */
    public record TodayRun(Long runId, String direction, OffsetDateTime departTime, String status) {
    }

    /** 경고·목록 전용 필드가 없는 등록 응답 — {@link #from(Bus, List)} 의 {@code warnings=null} 단축형. */
    public static BusResponse from(Bus bus) {
        return from(bus, null);
    }

    /** {@link Bus} 엔티티와 정원 축소 경고(있으면)를 응답으로 옮긴다 — 목록 전용 필드 셋은 싣지 않는다(키를 뺀다). */
    public static BusResponse from(Bus bus, List<BusWarning> warnings) {
        return new BusResponse(bus.getId(), bus.getBusNo(), bus.getPlateNo(), bus.getCapacity(),
                bus.getStudentCapacity(), bus.isOperable(), warnings, null, null, null);
    }

    /** 목록 항목 — 활성 편성 수·활성 스케줄 수·오늘 미취소 회차를 더한다(Ruling 816). 등록·수정 응답에는 없다. */
    public static BusResponse listItem(Bus bus, int routeCount, int scheduleCount, List<TodayRun> todayRuns) {
        return new BusResponse(bus.getId(), bus.getBusNo(), bus.getPlateNo(), bus.getCapacity(),
                bus.getStudentCapacity(), bus.isOperable(), null, routeCount, scheduleCount, todayRuns);
    }
}
