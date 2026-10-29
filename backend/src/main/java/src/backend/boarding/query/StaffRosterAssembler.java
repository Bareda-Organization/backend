package src.backend.boarding.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.StaffRosterItemResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;

/**
 * 관계자 웹 명단({@link StaffRosterItemResponse}) 조립 — 확정 뒤 회차는 {@code run_rider} 원본에서, 확정 전(idle)
 * 회차는 예정 명단에서 행을 만든다. 트랜잭션을 열지 않는다 — 호출하는 {@link RosterQueryService} 의 읽기
 * 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
class StaffRosterAssembler {

    private final RunRiderRepository runRiderRepository;

    private final ProjectedRosterReader projectedRosterReader;

    private final RosterDataReader reader;

    /** 예정 명단의 행 하나를 만드는 데 쓰는 조회 표 모음. */
    private record PlannedLookups(ProjectedRoster planned, Map<Long, Student> studentsById,
            Map<Long, Stop> stopsById, Map<Long, String> rawPhonesById, Map<Long, Long> transferIdsByStudent) {
    }

    /** 확정 뒤 회차의 명단 — {@code run_rider} 원본 그대로다. */
    List<StaffRosterItemResponse> confirmedItems(Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        Map<Long, Student> studentsById = reader.studentsOf(run.getAcademyId(), riders);
        Map<Long, String> rawPhonesById = reader.rawPhonesOf(run.getAcademyId(), studentsById.keySet());
        List<Long> stopIds = riders.stream().map(RunRider::getStopId).distinct().toList();
        Map<Long, Stop> stopsById = reader.stopsByIdOf(run.getAcademyId(), stopIds);
        Map<Long, RunStop> runStopsByStopId = reader.runStopsByStopId(run);
        return riders.stream()
                .map(rider -> toStaffItem(rider, studentsById.get(rider.getStudentId()), stopsById.get(rider.getStopId()),
                        runStopsByStopId.get(rider.getStopId()), rawPhonesById.get(rider.getStudentId())))
                .toList();
    }

    /**
     * 확정 전(idle) 회차의 예정 명단 — {@code run_rider} 는 확정이 채우므로 아직 비어 있다. 확정 배치와
     * §5.8 이동 판정이 쓰는 {@link ProjectedRosterReader} 규칙을 그대로 읽는다(요일별 주소 − 탑승 OFF +
     * 강제 추가 − 출발 이동 + 도착 이동) — 명단 표시용 계산을 따로 두면 화면에 보이는 학생과 이동 판정이
     * 어긋난다. 탑승 OFF·출발 이동으로 빠진 학생은 넣지 않고, 행은 {@code status=waiting} 이다.
     *
     * <p>{@code change} 는 확정이 붙일 값과 같다(Ruling 370) — 확정({@code RunRiderPersistence#ridersOf})이
     * {@code added} 를 붙이는 집합({@link ProjectedRoster#addedStudentIds()}: 강제 추가·도착 이동)이면
     * {@code added}, 아니면 없다. {@code transfer_id} 는 그중 도착 이동 대기 행에만 실린다(§5.8.1 취소용).
     */
    List<StaffRosterItemResponse> plannedItems(Run run) {
        ProjectedRoster planned = projectedRosterReader.read(run);
        Map<Long, Student> studentsById = reader.studentsByIdOf(run.getAcademyId(), planned.studentIds());
        Map<Long, String> rawPhonesById = reader.rawPhonesOf(run.getAcademyId(), studentsById.keySet());
        Map<Long, Stop> stopsById = reader.stopsByIdOf(run.getAcademyId(),
                planned.studentStops().values().stream().distinct().toList());
        Map<Long, Long> transferIdsByStudent = planned.incomingTransfers().stream()
                .collect(Collectors.toMap(RunTransfer::getStudentId, RunTransfer::getId, (first, duplicate) -> first));
        PlannedLookups lookups = new PlannedLookups(planned, studentsById, stopsById, rawPhonesById,
                transferIdsByStudent);
        return planned.studentIds().stream()
                .filter(studentsById::containsKey)
                .map(studentId -> toPlannedItem(studentId, lookups))
                .toList();
    }

    private StaffRosterItemResponse toPlannedItem(Long studentId, PlannedLookups lookups) {
        Student student = lookups.studentsById().get(studentId);
        Stop stop = lookups.stopsById().get(lookups.planned().studentStops().get(studentId));
        return new StaffRosterItemResponse(studentId, student.getName(), student.getClassName(),
                stop == null ? null : stop.getName(), null, null, lookups.rawPhonesById().get(studentId),
                lookups.planned().addedStudentIds().contains(studentId) ? lower(ChangeType.ADDED.name()) : null,
                lower(RiderStatus.WAITING.name()), student.getNote(), lookups.transferIdsByStudent().get(studentId));
    }

    private StaffRosterItemResponse toStaffItem(RunRider rider, Student student, Stop stop, RunStop runStop,
            String rawPhone) {
        return new StaffRosterItemResponse(rider.getStudentId(), student.getName(), student.getClassName(),
                stop == null ? null : stop.getName(), runStop == null ? null : runStop.getId(),
                runStop == null ? null : runStop.getSeq(), rawPhone,
                rider.getChange() == null ? null : lower(rider.getChange().name()), lower(rider.getStatus().name()),
                student.getNote(), null);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
