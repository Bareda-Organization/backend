package src.backend.student.access;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.student.repository.WeeklyAddressRepository;
import src.backend.student.repository.WeeklyAddressRepository.StopRiderCount;

/**
 * 학생이 그날 서는 승하차지를 다른 모듈(확정 명단 · 노선 계산 · 승인 요약)에 내주는 학생 모듈의 읽기 창구 — 요일별 주소 저장소를 모듈 밖에 열지 않고 검증을 통과한 칸({@code verified}, 승하차지 지정)만 돌려준다.
 * 학원 조건은 학생 조인으로 건다({@code weekly_address} 에 학원 컬럼이 없다, ERD §6.1).
 */
@Component
@RequiredArgsConstructor
public class DailyStopLookup {

    private final WeeklyAddressRepository weeklyAddressRepository;

    /** 주어진 학생들이 그 요일 · 방향에 서는 승하차지 — 학생 한 명당 최대 1행이다. */
    public List<StudentDailyStop> findDailyStops(Long academyId, Collection<Long> studentIds, Weekday weekday,
            Direction direction) {
        return weeklyAddressRepository.findDailyStops(academyId, studentIds, weekday, direction);
    }

    /** 주어진 승하차지에 서는 학생들의 그날 승하차지 — 운행일 0시({@code serviceDayStart}) 전에 퇴원한 학생은 뺀다. */
    public List<StudentDailyStop> findDailyStopsByStopIds(Long academyId, Collection<Long> stopIds, Weekday weekday,
            Direction direction, OffsetDateTime serviceDayStart) {
        return weeklyAddressRepository.findDailyStopsByStopIds(academyId, stopIds, weekday, direction, serviceDayStart);
    }

    /** 승하차지별로 그 요일 · 방향에 서는 재원 학생 수 — 학생이 없는 승하차지는 키가 없다(편성 화면 이용 인원). */
    public Map<Long, Long> countRidersByStop(Long academyId, Collection<Long> stopIds, Weekday weekday,
            Direction direction) {
        return weeklyAddressRepository.countRidersByStopIds(academyId, stopIds, weekday, direction).stream()
                .collect(Collectors.toMap(StopRiderCount::getStopId, StopRiderCount::getTotal));
    }

    /** 승하차지별로 요일 · 방향과 무관하게 그 승하차지를 쓰는 재원 학생 수 — 학생이 없는 승하차지는 키가 없다(승하차지 관리 목록). */
    public Map<Long, Long> countStudentsByStop(Long academyId, Collection<Long> stopIds) {
        return weeklyAddressRepository.countStudentsByStopIds(academyId, stopIds).stream()
                .collect(Collectors.toMap(StopRiderCount::getStopId, StopRiderCount::getTotal));
    }
}
