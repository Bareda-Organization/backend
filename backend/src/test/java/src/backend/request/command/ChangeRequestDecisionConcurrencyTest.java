package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.request.dto.DecideChangeRequestRequest;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.query.ApprovalQueryService;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * BR-171 — {@link ChangeRequestDecisionService#decide} 의 승인 배포는 회차를 잠그지 않아, 경유
 * 지점 재배포(라우팅 쪽 {@code WaypointStore}) 등 <b>같은 회차의 다른 배포</b>와 같은 순간이면
 * {@code route_version} 의 {@code (confirmed_route_id, version_no)} UNIQUE 를 어겨 500 이 난다.
 *
 * <p>경유 지점 쪽 배포 절차(잠금 획득 → 새 버전 저장 → {@code assignCurrentVersion})를 그대로 흉내낸
 * "경쟁 스레드"를 만들어 재현한다 — 실제 {@code WaypointCommandService}(라우팅 소유)를 호출하지
 * 않고 그 저장소 호출 순서만 복제한다({@code routing} 모듈 파일을 고치지 않기 위함, BRIEF-2A 경계
 * 규칙). 기법은 {@code ChangeRequestAutoRejectionConcurrencyTest}·{@code RunConfirmationConcurrencyTest}
 * 와 같되, 이 경쟁은 <b>같은 잠금을 다투지 않는다는 것 자체가 결함</b>이라 잠금 대기를 폴링할 필요가
 * 없다 — 경쟁 스레드가 잠금을 쥔 채 커밋을 늦추는 동안 승인 스레드가 옛 버전을 읽게 하는 것으로 충분하다.
 */
@SpringBootTest
class ChangeRequestDecisionConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 45;

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final ZoneOffset KST = ZoneOffset.of("+09:00");

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private ApprovalQueryService approvalQueryService;

    @Autowired
    private ChangeRequestDecisionService decisionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM change_request WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update(
                "DELETE FROM route WHERE bus_id IN (SELECT id FROM bus WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    /**
     * RED — 경유 지점 배포가 잠금을 쥐고 커밋을 늦추는 동안, 같은 회차의 승인 결정이 잠금 없이 같은
     * {@code currentVersionId} 를 읽어 같은 {@code version_no} 로 배포를 시도하면 UNIQUE 위반으로
     * 실패해야 한다(수정 전) — {@code decisionService.decide} 가 {@link DataIntegrityViolationException}
     * (또는 그것이 감싸는 언체크 예외)를 던지는 것을 눈으로 확인한다.
     */
    @Test
    @DisplayName("BR-171 — 경유 지점 배포와 승인 배포가 같은 순간이면 판본 번호가 충돌한다")
    void 경유지_배포와_승인_배포가_겹치면_판본_번호가_충돌한다() throws Exception {
        시나리오 s = 시나리오를_만든다();

        CountDownLatch 경유지_배포가_버전을_저장했다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Exception 승인측_예외 = null;
        try {
            Future<Void> 경유지_배포 = pool.submit(() -> {
                경유지_배포를_흉내내고_커밋을_늦춘다(s.runId, s.academyId, 경유지_배포가_버전을_저장했다);
                return null;
            });
            Future<Exception> 승인_결정 = pool.submit(() -> {
                경유지_배포가_버전을_저장했다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    decisionService.decide(관계자(s.academyId), s.approvalId,
                            new DecideChangeRequestRequest(true, null, s.previewToken));
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });

            승인측_예외 = 승인_결정.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            경유지_배포.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(승인측_예외)
                .as("경유 지점 배포와 같은 순간이어도 승인 배포는 같은 잠금으로 순서를 기다렸다가 성공해야 한다"
                        + "(RED — 수정 전에는 잠금이 없어 UNIQUE 위반으로 이 단언이 실패한다)")
                .isNull();

        Integer finalVersionNo = jdbcTemplate.queryForObject(
                "SELECT version_no FROM route_version v JOIN confirmed_route cr "
                        + "ON cr.current_version_id = v.id WHERE cr.run_id = ?",
                Integer.class, s.runId);
        assertThat(finalVersionNo).as("경유 지점 배포(N+1) 뒤 승인 배포가 이어져 N+2 가 최종 버전이어야 한다")
                .isEqualTo(s.baseVersionNo + 2);
    }

    /** 경유 지점 배포(라우팅 소유)의 저장소 호출 순서만 복제한다 — 실제 {@code WaypointStore} 를 부르지 않는다. */
    private void 경유지_배포를_흉내내고_커밋을_늦춘다(long runId, long academyId, CountDownLatch 저장했다) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            runRepository.findLockedByIdAndAcademyId(runId, academyId).orElseThrow();
            ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId).orElseThrow();
            RouteVersion current = routeVersionRepository.findById(confirmedRoute.getCurrentVersionId())
                    .orElseThrow();
            RouteVersion competing = RouteVersion.forConfirmedRoute(runId, current.getVersionNo() + 1,
                    RouteVersionSource.WAYPOINT, current.getEstDurationMin(), current.getEstDistanceKm(),
                    OffsetDateTime.now(KST), "concurrency-test-fingerprint", current.getEngineName(),
                    Map.of(), false, null, null, OffsetDateTime.now(KST));
            routeVersionRepository.save(competing);
            confirmedRouteRepository.assignCurrentVersion(runId, competing.getId());
            저장했다.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private 시나리오 시나리오를_만든다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);

        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures.stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures.stop(academyId, "37.564000", "126.974000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        long 학생1 = fixtures.student(academyId, "학생1");
        long 학생2 = fixtures.student(academyId, "학생2");
        long 학생3 = fixtures.student(academyId, "학생3");
        fixtures.verifiedAddress(학생1, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures.verifiedAddress(학생2, midStop, WEEKDAY, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures.verifiedAddress(학생3, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.564000", "126.974000");

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(KST);
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);

        long parentAccountId = accountRepository
                .save(Account.forSignup(academyId, "parent-" + runId, "hash", "학부모", "010-0000-0000", null,
                        Role.PARENT))
                .getId();

        ChangeRequest changeRequest = ChangeRequest.forRequest(academyId, runId, 학생2,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, parentAccountId,
                OffsetDateTime.now());
        long approvalId = changeRequestRepository.save(changeRequest).getId();

        String previewToken = approvalQueryService.detail(관계자(academyId), approvalId).previewToken();

        Integer baseVersionNo = jdbcTemplate.queryForObject(
                "SELECT version_no FROM route_version v JOIN confirmed_route cr "
                        + "ON cr.current_version_id = v.id WHERE cr.run_id = ?",
                Integer.class, runId);

        return new 시나리오(academyId, runId, approvalId, previewToken, baseVersionNo);
    }

    private AuthUser 관계자(long academyId) {
        return new AuthUser(academyId * 1000 + 1, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private static final class 시나리오 {
        final long academyId;
        final long runId;
        final long approvalId;
        final String previewToken;
        final int baseVersionNo;

        시나리오(long academyId, long runId, long approvalId, String previewToken, int baseVersionNo) {
            this.baseVersionNo = baseVersionNo;
            this.academyId = academyId;
            this.runId = runId;
            this.approvalId = approvalId;
            this.previewToken = previewToken;
        }
    }
}
