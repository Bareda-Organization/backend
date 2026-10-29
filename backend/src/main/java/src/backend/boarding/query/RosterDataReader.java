package src.backend.boarding.query;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.ManagerRosterResponse.NoShowCountdown;
import src.backend.boarding.entity.RunRider;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.NoShowContactView;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.entity.NoShowContact;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.repository.NoShowContactRepository;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianPhone;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 회차 명단 조립에 쓰는 조회 모음 — 학생·정차지·보호자 전화·정차 항목·미승차 카운트다운을 저장소에서 읽어
 * 조립 쪽이 쓰는 표(id → 값)로 돌려준다. 트랜잭션을 열지 않는다 — 호출하는 {@link RosterQueryService} 의
 * 읽기 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
class RosterDataReader {

    private final BusRepository busRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    private final NoShowContactRepository noShowContactRepository;

    /** 열린 미승차 케이스를 탑승자별 카운트다운으로 — 연락 이력은 케이스 전체를 쿼리 한 번으로 읽어 시각순으로 붙인다(Ruling 823). */
    Map<Long, NoShowCountdown> countdownsOf(Long runId, Long academyId) {
        List<NoShowCase> openCases = noShowCaseRepository.findOpenByRunIdAndAcademyId(runId, academyId);
        Map<Long, List<NoShowContactView>> contactsByCaseId = openCases.isEmpty() ? Map.of()
                : noShowContactRepository.findAllByNoShowCaseIdInOrderByAttemptedAtAscIdAsc(
                        openCases.stream().map(NoShowCase::getId).toList()).stream()
                        .collect(Collectors.groupingBy(NoShowContact::getNoShowCaseId,
                                Collectors.mapping(NoShowContactView::from, Collectors.toList())));
        return openCases.stream().collect(Collectors.toMap(NoShowCase::getRunRiderId,
                noShowCase -> new NoShowCountdown(String.valueOf(noShowCase.getId()), noShowCase.getStartedAt(),
                        noShowCase.getExpiresAt(), contactsByCaseId.getOrDefault(noShowCase.getId(), List.of()))));
    }

    /**
     * 확정 노선의 정차 항목 중 <b>학생 승하차지</b>와 등원 <b>학원 항목</b>을 순번대로 고른다 — 강제
     * 경유지(RTE-10)는 도착 처리 대상이 아니라 뺀다. 학원 항목을 빼면 앱에 등원 종료 수단이 없다(Ruling 327).
     */
    List<RunStop> boardingStopsOf(AuthUser requester, Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                requester.academyId());
        return runStops.stream().filter(stop -> stop.getStopId() != null || stop.isDestination()).toList();
    }

    /**
     * 확정 노선의 현재 버전에서 승하차지 id → 정차 항목(run_stop)을 찾는 표 — 관계자 명단 행의 {@code stop_id}·{@code stop_seq}
     * 출처다(§5.4, Ruling 811). 학원은 요청자가 아니라 회차 소속이다(메인 관리자 토큰은 학원 id 가 없다).
     */
    Map<Long, RunStop> runStopsByStopId(Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return Map.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, run.getAcademyId())
                .stream()
                .filter(runStop -> runStop.getStopId() != null)
                .collect(Collectors.toMap(RunStop::getStopId, runStop -> runStop, (first, duplicate) -> first));
    }

    /** 승하차지 id → 승하차지 표. */
    Map<Long, Stop> stopsByIdOf(Long academyId, Collection<Long> stopIds) {
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    /** 학생 id → 학생 표 — 대상 id 를 직접 준다. */
    Map<Long, Student> studentsByIdOf(Long academyId, Collection<Long> studentIds) {
        return studentRepository.findAllByAcademyIdAndIdIn(academyId, studentIds).stream()
                .collect(Collectors.toMap(Student::getId, student -> student));
    }

    /** 탑승자들의 학생 id → 학생 표. */
    Map<Long, Student> studentsOf(Long academyId, List<RunRider> riders) {
        List<Long> studentIds = riders.stream().map(RunRider::getStudentId).distinct().toList();
        return studentsByIdOf(academyId, studentIds);
    }

    /** 학생 id → 마스킹한 보호자 전화 표. */
    Map<Long, String> maskedPhonesOf(Long academyId, Set<Long> studentIds) {
        return phonesOf(academyId, studentIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> GuardianPhoneMasker.mask(entry.getValue())));
    }

    /** 학생 id → 보호자 전화 원본 표. */
    Map<Long, String> rawPhonesOf(Long academyId, Set<Long> studentIds) {
        return phonesOf(academyId, studentIds);
    }

    /** 회차의 버스 번호 — 버스가 없으면 {@code null}. */
    String busNoOf(AuthUser requester, Run run) {
        return busRepository.findByIdAndAcademyId(run.getBusId(), requester.academyId())
                .map(Bus::getBusNo)
                .orElse(null);
    }

    /** 학생 1명에 보호자가 여럿이면 조회가 이미 고정한 정렬의 <b>첫 값</b>만 남긴다(원본과 같은 근거). */
    private Map<Long, String> phonesOf(Long academyId, Set<Long> studentIds) {
        List<GuardianPhone> phones = guardianStudentRepository.findGuardianPhonesByAcademyId(academyId,
                studentIds.stream().toList());
        Map<Long, String> result = new LinkedHashMap<>();
        for (GuardianPhone phone : phones) {
            result.putIfAbsent(phone.getStudentId(), phone.getPhone());
        }
        return result;
    }
}
