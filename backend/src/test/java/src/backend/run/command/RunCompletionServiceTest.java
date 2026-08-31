package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link RunCompletionService#completeIfAllAlighted} 회귀 시험(Phase 9 T2 목표 10, 조율자 지정
 * 최우선 미완 항목) — 이전 세션이 직접 진단한 대로 {@code BOARDED} 카운팅이 {@code ALIGHTED} 로
 * 되돌아가도 그때까지의 시험 묶음은 계속 통과했다({@code report-p9-t2.md} §5-1). {@code absent} 학생이
 * 섞인 하원 회차에서 <b>마지막 탑승자가 하차하는 순간에만</b> {@code finished} 전이가 걸리는지를
 * 직접 검사해 그 결함을 고정한다.
 *
 * <p>{@code @Transactional} 을 쓴다({@code RunConfirmationServiceTest} 와 같은 근거) —
 * {@link RunCompletionService#completeIfAllAlighted} 는 {@code propagation = MANDATORY} 라 이미
 * 열린 트랜잭션이 있어야 호출할 수 있고, 테스트 트랜잭션이 그 자리를 대신한다.
 */
@SpringBootTest
@Transactional
class RunCompletionServiceTest {

    @Autowired
    private RunCompletionService completionService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    @Test
    @DisplayName("목표10 회귀 — absent 1명이 섞인 하원 회차는 마지막 탑승자가 하차하는 순간에만 종료된다")
    void absent가_섞인_회차는_마지막_탑승자_하차_순간에만_종료된다() {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, departTime);

        long absentStudentId = fixtures.student(academyId, "결석학생");
        fixtures.rider(runId, absentStudentId, stopId, RiderStatus.ABSENT, OffsetDateTime.now(clock));
        long boardedStudent1Id = fixtures.student(academyId, "탑승학생1");
        long rider1Id = fixtures.rider(runId, boardedStudent1Id, stopId, RiderStatus.BOARDED, OffsetDateTime.now(clock));
        long boardedStudent2Id = fixtures.student(academyId, "탑승학생2");
        long rider2Id = fixtures.rider(runId, boardedStudent2Id, stopId, RiderStatus.BOARDED, OffsetDateTime.now(clock));

        // 최종 지점 도착 처리에서 잔류가 있어 종료를 보류한 상태를 재현한다(§4.5, RunArrivalCommandService).
        Run run = runRepository.findById(runId).orElseThrow();
        run.deferFinish();
        runRepository.save(run);

        // 둘 다 탑승 중 — absent 학생이 섞여 있어도 종료되면 안 된다.
        assertThat(completionService.completeIfAllAlighted(run, OffsetDateTime.now(clock)))
                .as("탑승자가 남아 있으면 종료되면 안 된다").isFalse();
        assertThat(run.getStatus()).isEqualTo(RunStatus.MOVING);

        // 첫 탑승자만 하차 — 아직 1명이 남아 있으므로 종료되면 안 된다.
        RunRider rider1 = runRiderRepository.findById(rider1Id).orElseThrow();
        rider1.alight(OffsetDateTime.now(clock));
        runRiderRepository.save(rider1);

        assertThat(completionService.completeIfAllAlighted(run, OffsetDateTime.now(clock)))
                .as("아직 1명이 탑승 중이므로 종료되면 안 된다").isFalse();
        assertThat(run.getStatus()).isEqualTo(RunStatus.MOVING);

        // 마지막 탑승자 하차 — 이 순간 finished 로 전이돼야 한다. absent 학생은 그대로 잔류한다.
        RunRider rider2 = runRiderRepository.findById(rider2Id).orElseThrow();
        rider2.alight(OffsetDateTime.now(clock));
        runRiderRepository.save(rider2);

        assertThat(completionService.completeIfAllAlighted(run, OffsetDateTime.now(clock)))
                .as("마지막 탑승자가 하차하는 순간 종료돼야 한다(absent 잔류와 무관)").isTrue();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FINISHED);
    }
}
