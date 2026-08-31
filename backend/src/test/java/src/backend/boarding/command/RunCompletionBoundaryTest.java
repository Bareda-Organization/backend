package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.event.RunEndedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.RunCompletionService;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * T2↔T3 경계 — 하원 자동 종료(목표 10, T2 소유) 판정 서비스를 T3 이 호출하는 지점만 검사한다.
 * {@link RunCompletionService} 전이 로직 자체는 여기서 구현·검사하지 않는다(브리프의 T2/T3 경계) —
 * 이 워크트리의 {@link RunCompletionService} 는 항상 {@code false} 를 반환하는 T2 병합 전 자리표시자라
 * {@code @MockitoBean} 으로 대체해 ①마지막 하차 시 호출됐는가 ②반환값에 따라 {@link RunEndedEvent}
 * 발행이 갈리는가 두 가지만 확인한다.
 */
@SpringBootTest
@RecordApplicationEvents
@Transactional
class RunCompletionBoundaryTest {

    @Autowired
    private BoardingCommandService boardingCommandService;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private RunCompletionService runCompletionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    private BoardingCommandFixtures fixtures;

    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingCommandFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, runRiderRepository, confirmedRouteRepository,
                    routeVersionRepository, runStopRepository, jdbcTemplate, entityManager);
        }
        return fixtures;
    }

    @Test
    @DisplayName("경계 — 마지막 하차 처리 시 RunCompletionService 를 호출하고, true 면 RunEndedEvent 를 발행한다")
    void 마지막_하차_시_호출하고_true면_이벤트를_발행한다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        // 실제 완료 판정(잔여 BOARDED 수)이 이 시험에 섞이지 않도록 no_show 학생을 명단에 함께 둔다
        // — RunCompletionService 를 가짜로 대체했으므로 이 로스터 구성 자체는 반환값에 영향을 주지
        // 않지만, 조율자 지시대로 완료 판정 시험에 결근·미승차 탑승자가 섞인 명단을 쓴다.
        long alightingStudent = fixtures().student(academyId, "학생7");
        long noShowStudent = fixtures().student(academyId, "학생8(결근)");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long alightingRiderId = fixtures().runRider(runId, alightingStudent, stopId);
        long noShowRiderId = fixtures().runRider(runId, noShowStudent, stopId);
        jdbcTemplate.update("UPDATE run_rider SET status = 'no_show' WHERE id = ?", noShowRiderId);
        entityManager.clear();
        long escortAccountId = fixtures().escortAccount(academyId);
        AuthUser escort = new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE);
        Run run = runRepository.findById(runId).orElseThrow();

        when(runCompletionService.completeIfAllAlighted(eq(run), any())).thenReturn(true);

        boardingCommandService.updateStatus(escort, runId, alightingRiderId,
                new RiderStatusUpdateRequest("alighted", "manual", UUID.randomUUID(), now));

        verify(runCompletionService).completeIfAllAlighted(eq(run), any());
        List<RunEndedEvent> runEndedEvents = applicationEvents.stream(RunEndedEvent.class).toList();
        assertThat(runEndedEvents).as("①true 반환 시 RunEndedEvent 발행").hasSize(1);
        // no_show 탑승자는 ALIGHTED 가 아니라 집계에서 빠져야 한다 — 방금 하차한 1명만 세는지가
        // 이 단언의 본체다(리뷰 R2 판정문 §②변형9 — 값을 안 보면 집계 대상이 뒤바뀌어도 못 잡는다).
        assertThat(runEndedEvents.get(0).autoAlightedCount()).as("②ALIGHTED 1명만 센다").isEqualTo(1L);
    }

    @Test
    @DisplayName("경계 — RunCompletionService 가 false 를 반환하면 RunEndedEvent 를 발행하지 않는다")
    void false면_이벤트를_발행하지_않는다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long alightingStudent = fixtures().student(academyId, "학생9");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long alightingRiderId = fixtures().runRider(runId, alightingStudent, stopId);
        long escortAccountId = fixtures().escortAccount(academyId);
        AuthUser escort = new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE);
        Run run = runRepository.findById(runId).orElseThrow();

        when(runCompletionService.completeIfAllAlighted(eq(run), any())).thenReturn(false);

        boardingCommandService.updateStatus(escort, runId, alightingRiderId,
                new RiderStatusUpdateRequest("alighted", "manual", UUID.randomUUID(), now));

        verify(runCompletionService).completeIfAllAlighted(eq(run), any());
        assertThat(applicationEvents.stream(RunEndedEvent.class)).as("②false 반환 시 RunEndedEvent 미발행")
                .isEmpty();
    }
}
