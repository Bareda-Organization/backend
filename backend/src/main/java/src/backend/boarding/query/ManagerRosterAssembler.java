package src.backend.boarding.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.dto.ManagerRosterResponse.Counts;
import src.backend.boarding.dto.ManagerRosterResponse.NoShowCountdown;
import src.backend.boarding.dto.ManagerRosterResponse.RosterStudent;
import src.backend.boarding.dto.ManagerRosterResponse.StopGroup;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;

/**
 * 매니저 앱 명단({@link ManagerRosterResponse}) 조립 — 회차 접근·확정 여부를 판정하고 승하차지별 묶음을 만든다.
 * 트랜잭션을 열지 않는다 — 호출하는 {@link RosterQueryService} 의 읽기 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
class ManagerRosterAssembler {

    private final ManagerRunAccess managerRunAccess;

    private final RunRiderRepository runRiderRepository;

    private final AcademyRepository academyRepository;

    private final RosterDataReader reader;

    /** 승하차지 묶음 하나를 만드는 데 쓰는 조회 표 모음. */
    private record StopGroupLookups(Map<Long, Student> studentsById, Map<Long, String> maskedPhonesById,
            Map<Long, NoShowCountdown> countdownsByRiderId, Map<Long, Stop> stopsById,
            Map<Long, List<RunRider>> ridersByStopId, Academy academy) {
    }

    /** 매니저 명단 조립 — 읽기 트랜잭션 하나 안에서 돈다(감사 기록은 이 뒤에 호출부가 한다). */
    ManagerRosterResponse assemble(AuthUser requester, Long runId) {
        Run run = requireConfirmedRun(requester, runId);
        String busNo = reader.busNoOf(requester, run);
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        Map<Long, Student> studentsById = reader.studentsOf(run.getAcademyId(), riders);
        Map<Long, String> maskedPhonesById = reader.maskedPhonesOf(run.getAcademyId(), studentsById.keySet());
        List<RunStop> boardingStops = reader.boardingStopsOf(requester, run);
        Map<Long, Stop> stopsById = reader.stopsByIdOf(run.getAcademyId(),
                boardingStops.stream().map(RunStop::getStopId).filter(Objects::nonNull).toList());
        Academy academy = academyRepository.findById(run.getAcademyId()).orElse(null);
        Map<Long, List<RunRider>> ridersByStopId = riders.stream().collect(Collectors.groupingBy(RunRider::getStopId));
        StopGroupLookups lookups = new StopGroupLookups(studentsById, maskedPhonesById,
                reader.countdownsOf(run.getId(), run.getAcademyId()), stopsById, ridersByStopId, academy);
        List<StopGroup> stops = boardingStops.stream().map(runStop -> groupOf(runStop, lookups)).toList();
        return new ManagerRosterResponse(String.valueOf(run.getId()), busNo, lower(run.getDirection().name()),
                countsOf(riders), stops);
    }

    /** 회차 접근 판정 뒤 확정 전(idle) 회차를 막는다 — 접근 판정이 먼저다. */
    private Run requireConfirmedRun(AuthUser requester, Long runId) {
        ManagerRunAccess.RunAssignment assigned = managerRunAccess.requireAssignedRun(requester, runId);
        Run run = assigned.run();
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        return run;
    }

    /** 정차 항목 하나를 학원 항목 또는 승하차지 묶음으로. */
    private StopGroup groupOf(RunStop runStop, StopGroupLookups lookups) {
        if (runStop.isDestination()) {
            return destinationGroupOf(runStop, lookups.academy());
        }
        return toStopGroup(runStop, lookups.stopsById().get(runStop.getStopId()),
                lookups.ridersByStopId().getOrDefault(runStop.getStopId(), List.of()), lookups);
    }

    private StopGroup toStopGroup(RunStop runStop, Stop stopInfo, List<RunRider> ridersAtStop,
            StopGroupLookups lookups) {
        List<RosterStudent> students = ridersAtStop.stream()
                // absent 는 개인 행을 빼되, 버스 간 이동으로 빠진 removed 행은 빨강으로 보인다(RTE-04 · §4.2)
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT || rider.getChange() == ChangeType.REMOVED)
                .map(rider -> toRosterStudent(rider, lookups.studentsById().get(rider.getStudentId()),
                        lookups.maskedPhonesById().get(rider.getStudentId()),
                        lookups.countdownsByRiderId().get(rider.getId())))
                .toList();
        String name = stopInfo == null ? null : stopInfo.getName();
        String address = stopInfo == null ? null : stopInfo.getAddress();
        return new StopGroup(String.valueOf(runStop.getId()), runStop.getSeq(), name, address,
                runStop.getChange() == null ? null : lower(runStop.getChange().name()), runStop.getSkipNotice(),
                runStop.getArrivedAt(), false, students);
    }

    /** 등원 회차의 학원 항목(Ruling 327) — 탈 학생이 없고 이름·주소는 학원 것이다. */
    private StopGroup destinationGroupOf(RunStop runStop, Academy academy) {
        return new StopGroup(String.valueOf(runStop.getId()), runStop.getSeq(),
                academy == null ? null : academy.getName(), academy == null ? null : academy.getAddress(), null, null,
                runStop.getArrivedAt(), true, List.of());
    }

    /** {@code countdown} 은 {@code no_show} 학생에게만 싣는다 — 되돌려 종결된 케이스는 조회에서 이미 빠진다. */
    private RosterStudent toRosterStudent(RunRider rider, Student student, String maskedPhone,
            NoShowCountdown countdown) {
        return new RosterStudent(String.valueOf(rider.getId()), String.valueOf(rider.getStudentId()),
                student.getName(), student.getPhotoUrl(), student.getClassName(), maskedPhone, student.getNote(),
                student.isCanGoAlone(), lower(rider.getStatus().name()),
                rider.getChange() == null ? null : lower(rider.getChange().name()),
                rider.getStatus() == RiderStatus.NO_SHOW ? countdown : null);
    }

    private Counts countsOf(List<RunRider> riders) {
        long boarded = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.BOARDED).count();
        long waiting = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.WAITING).count();
        long noShow = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.NO_SHOW).count();
        // 다른 버스로 옮긴 학생(removed)은 미등원이 아니다
        long absentN = riders.stream()
                .filter(rider -> rider.getStatus() == RiderStatus.ABSENT && rider.getChange() != ChangeType.REMOVED)
                .count();
        return new Counts(boarded, waiting, noShow, absentN);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
