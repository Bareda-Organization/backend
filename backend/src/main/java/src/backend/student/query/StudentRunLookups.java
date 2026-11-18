package src.backend.student.query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.rider.RunRiderReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.request.query.BoardingIntentReadQueryService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.entity.Student;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 학생 당일 회차 목록이 회차마다 필요로 하는 값(차량 · 탑승자 · 탑승 의사 · 고정 노선 승하차지)을 회차 수와 무관한 질의 수로 한꺼번에 읽는다 —
 * 회차마다 따로 읽으면 학생이 탄 회차가 늘수록 질의가 같은 수만큼 는다.
 */
@Component
@RequiredArgsConstructor
class StudentRunLookups {

    private final RunRiderReader runRiderReader;

    private final BoardingIntentReadQueryService boardingIntentReadQueryService;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final BusRepository busRepository;

    /** 한 학생의 회차 목록에 필요한 조회 결과 — 회차 번호 · 차량 번호 · 방향으로 찾는다. */
    record Lookups(Map<Long, RunRiderReader.Entry> riders, Map<Direction, Long> idleStopByDirection,
            Map<Long, Bus> buses, Map<Long, BoardingIntentReadQueryService.Intent> intents) {
    }

    /** 확정 이후 회차는 탑승자 행에서, 확정 전 회차는 그 요일의 고정 노선 주소에서 승하차지를 읽는다. */
    Lookups read(Student student, List<Run> runs) {
        Long academyId = student.getAcademyId();
        List<Long> confirmedRunIds = runs.stream().filter(run -> run.getStatus() != RunStatus.IDLE).map(Run::getId)
                .toList();
        Map<Long, RunRiderReader.Entry> riders = confirmedRunIds.isEmpty() ? Map.of()
                : runRiderReader.findRiders(academyId, student.getId(), confirmedRunIds);
        Map<Long, Bus> buses = busRepository.findAllByAcademyIdAndIdIn(academyId,
                runs.stream().map(Run::getBusId).distinct().toList()).stream()
                .collect(Collectors.toMap(Bus::getId, Function.identity()));
        return new Lookups(riders, idleStops(student, runs), buses, intents(student, runs));
    }

    private Map<Direction, Long> idleStops(Student student, List<Run> runs) {
        Map<Direction, Long> stops = new HashMap<>();
        runs.stream().filter(run -> run.getStatus() == RunStatus.IDLE).findFirst().ifPresent(idle ->
                weeklyAddressRepository.findAllByAcademyIdAndStudentIdAndWeekdayAndVerifiedTrueAndStopIdIsNotNull(
                        student.getAcademyId(), student.getId(), Weekday.of(idle.getServiceDate()))
                        .forEach(address -> stops.put(address.getDirection(), address.getStopId())));
        return stops;
    }

    private Map<Long, BoardingIntentReadQueryService.Intent> intents(Student student, List<Run> runs) {
        return boardingIntentReadQueryService.findIntents(student.getAcademyId(), student.getId(),
                runs.stream().map(Run::getId).toList());
    }
}
