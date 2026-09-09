package src.backend.schedule.dto;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import src.backend.schedule.entity.Schedule;

/**
 * 스케줄 응답(API_SPEC §5.10) — 목록·등록·수정이 함께 쓴다. 관계자 웹 전용이라 역할별로 가르지
 * 않는다(스케줄은 학부모·매니저 앱 응답에 실리지 않는다).
 *
 * <p>{@code busNo} 를 함께 싣는 이유는 목록 화면이 차량을 <b>번호로</b> 보여 주기 때문이다 —
 * 빼면 클라이언트가 행마다 차량을 다시 조회해야 한다.
 *
 * @param departTime {@code HH:mm} — 날짜가 없는 시각이고, 회차 생성 시 {@code service_date} 와 합쳐진다
 */
public record ScheduleResponse(Long id, Long busId, String busNo, String weekday, String direction,
        String departTime, String originName, String destinationName, Integer estDurationMin, boolean active) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    public static ScheduleResponse of(Schedule schedule, String busNo) {
        return new ScheduleResponse(schedule.getId(), schedule.getBusId(), busNo,
                lower(schedule.getWeekday().name()), lower(schedule.getDirection().name()),
                schedule.getDepartTime().format(TIME_FORMAT), schedule.getOriginName(),
                schedule.getDestinationName(), schedule.getEstDurationMin(), schedule.isActive());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
