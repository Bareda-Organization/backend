package src.backend.location.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.proximity.ProximityFixtures;
import src.backend.location.proximity.ProximityNotificationService;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.redis.RedisTestContainerBase;

/**
 * 근접 알림 배치({@link ProximityNotificationScheduler}) 수준의 검증(Phase 10 이월 ②, Phase 11
 * 목표 12·13) — {@code ProximityNotificationServiceTest} 가 회차 1건의 판정 결과(알림·문턱거리·ABSENT
 * 배제)를 보는 것과 달리, 이 클래스는 <b>여러 회차 중 무엇을 판정 대상으로 고르는지</b>와
 * <b>인스턴스가 2개일 때 판정을 몇 번 수행하는지</b>를 본다({@code RunConfirmationSchedulerTest} 가
 * 본보기).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@code judgeOne} 은 회차별로 각각 커밋되고,
 * {@link ProximityNotificationService} 를 스파이로 감싸면서까지 테스트 트랜잭션에 묶으면 스파이가
 * 가로챈 호출과 실제 커밋 시점이 갈릴 수 있다({@code RunConfirmationSchedulerTest} 와 같은 근거).
 * 뒷정리는 {@link ProximityFixtures} 가 심는 학원 이름으로 표시된 행을 직접 지운다.
 *
 * <p><b>{@link ProximityNotificationService} 를 스파이로 감싸는 이유</b> — 배치 대상 선정(어떤
 * 회차의 {@code judgeOne} 을 부르는가)과 회차 1건의 판정 로직(내부에서 무엇을 하는가)은 서로 다른
 * 검증 대상이다. 실제 위치·거리 데이터를 매번 갖추지 않고도 "그 회차가 호출됐는가" 만 볼 수 있어야
 * BATCH_SIZE·MOVING 필터 시험이 좁아진다. 예외 격리 시험은 이 스파이에 {@code doThrow} 를 심어
 * <b>실제로 발생 가능한 예외</b>(회차 1건 처리 중 알 수 없는 실패)를 흉내낸다 —
 * {@code StaffApprovalDecideAtomicityTest} 가 저장소 스파이에 쓰는 것과 같은 기법이다.
 */
@SpringBootTest
class ProximityNotificationSchedulerTest extends RedisTestContainerBase {

    /** 정차지 좌표(37.500000, 127.000000) 기준 약 200m — 300m 문턱 안쪽({@code ProximityNotificationServiceTest} 와 같은 값). */
    private static final String NEAR_LAT = "37.501799";

    private static final String STOP_LNG = "127.000000";

    @Autowired
    private ProximityNotificationScheduler scheduler;

    @MockitoSpyBean
    private ProximityNotificationService proximityNotificationService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    /**
     * 스파이 스터빙과 목표12 시험이 심은 shedlock 행을 함께 정리한다 — 스터빙을 남기면 다음 시험이
     * 남의 회차 ID 에 대한 {@code doThrow} 를 물려받을 일은 없지만(인자가 회차 ID 라 매번 새 값이다),
     * 심은 미래 {@code lock_until} 을 풀지 않으면 다음 시험의 {@code judgeMovingRuns()} 호출을 막는다
     * ({@code StaffApprovalDecideAtomicityTest} 의 {@code reset()} 관례를 함께 따른다).
     *
     * <p><b>행을 지우지 않고 과거 시각으로 갱신하는 이유</b> — {@code JdbcTemplateLockProvider} 는
     * 이름별로 한 번 행이 생기고 나면 그 뒤로는 값싼 {@code UPDATE ... WHERE lock_until <= now} 만
     * 시도하고 행이 없을 때 쓰는 {@code INSERT ... ON CONFLICT} 업서트로 되돌아가지 않는다 —
     * {@code DELETE} 로 행 자체를 지우면 그 갱신 대상이 없어 매번 "잠겨 있다" 로 오판된다(실측:
     * 첫 시험만 upsert 로 락을 얻고 이후 전부 대상 0건 갱신으로 스킵됨).
     */
    @AfterEach
    void tearDown() {
        reset(proximityNotificationService);
        jdbcTemplate.update(
                "UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) - interval '1 hour' "
                        + "WHERE name = 'proximity-notification'");
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'approaching:%'");
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    private ProximityFixtures fixtures() {
        return new ProximityFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
    }

    /** {@link #fullyWiredMovingRun} 의 반환값 — {@code run_stop.proximity_notified_at} 조회에 stopId 가 필요하다. */
    private record WiredRun(long runId, long stopId) {
    }

    /** 근접 판정 대상이 되도록 정차지·노선·라이더·위치까지 완비한 MOVING 회차 1건. */
    private WiredRun fullyWiredMovingRun(ProximityFixtures fx, long academyId, long busId, String studentName,
            String guardianName) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, studentName);
        fx.guardianOf(academyId, studentId, guardianName, now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);
        writePosition(runId, NEAR_LAT, STOP_LNG);
        return new WiredRun(runId, stopId);
    }

    /**
     * {@code confirmIfIdle} 까지만 밟아 {@code start} 를 부르지 않은 CONFIRMED 회차 —
     * {@link ProximityFixtures#movingRun} 의 앞 절반과 같되 운행 중으로 전이하지 않는다.
     * {@code judgeMovingRuns()} 가 이 회차를 대상으로 삼지 않아야 한다(MOVING 필터).
     */
    private long confirmedNotMovingRun(long academyId, long busId, OffsetDateTime departTime) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.FROM_ACADEMY,
                departTime, departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, departTime.minusMinutes(30));
        return runId;
    }

    @Test
    void MOVING_상태만_판정_대상이_된다() {
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long movingRunId = fullyWiredMovingRun(fx, academyId, busId, "근접학생1", "근접학부모1").runId();
        long confirmedRunId = confirmedNotMovingRun(academyId, fx.bus(academyId),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

        scheduler.judgeMovingRuns();

        verify(proximityNotificationService, times(1)).judgeOne(eq(movingRunId), eq(academyId));
        verify(proximityNotificationService, never()).judgeOne(eq(confirmedRunId), anyLong());
    }

    @Test
    void 한_틱은_배치_크기_상한을_넘지_않는다() {
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime baseDepart = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        int total = ProximityNotificationScheduler.BATCH_SIZE + 1;
        for (int i = 0; i < total; i++) {
            OffsetDateTime departTime = baseDepart.plusMinutes(i);
            fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, departTime, departTime.minusMinutes(30),
                    departTime.minusMinutes(30));
        }

        scheduler.judgeMovingRuns();

        // 대상 선정 질의(findByStatusAndCanceledAtIsNullOrderByIdAsc)는 학원으로 좁히지 않는
        // 전역 조회다(목표 6 이 지키는 것도 "이 학원의 상한"이 아니라 "한 틱 전체의 상한") — 그래서
        // 이 학원 것만 세면(eq(academyId)) 다른 학원의 기존 MOVING 회차가 상한 한 자리를 먼저
        // 차지했을 때 49건으로 어긋난다. anyLong() 으로 전체를 세야 그 환경 요인과 무관하게 성립한다.
        verify(proximityNotificationService, times(ProximityNotificationScheduler.BATCH_SIZE))
                .judgeOne(anyLong(), anyLong());
    }

    @Test
    void 한_회차의_예외가_다른_회차_판정을_막지_않는다() {
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        // ID 오름차순으로 도는 루프이므로, 예외를 던질 회차를 먼저 만들어야 "앞에서 터지면 뒤가
        // 막히는지" 를 실제로 검증한다 — 순서를 반대로 두면 try-catch 를 지워도 우연히 통과한다.
        WiredRun badRun = fullyWiredMovingRun(fx, academyId, busId, "근접학생불량", "근접학부모불량");
        WiredRun goodRun = fullyWiredMovingRun(fx, academyId, busId, "근접학생정상", "근접학부모정상");

        doThrow(new RuntimeException("의도적 실패 — 배치 격리 시험")).when(proximityNotificationService)
                .judgeOne(eq(badRun.runId()), anyLong());

        scheduler.judgeMovingRuns();

        verify(proximityNotificationService, times(1)).judgeOne(eq(badRun.runId()), eq(academyId));
        OffsetDateTime proximityNotifiedAt = jdbcTemplate.queryForObject(
                "SELECT rs.proximity_notified_at FROM run_stop rs "
                        + "JOIN route_version rv ON rs.route_version_id = rv.id "
                        + "JOIN confirmed_route cr ON rv.confirmed_route_id = cr.run_id "
                        + "WHERE cr.run_id = ? AND rs.stop_id = ?",
                OffsetDateTime.class, goodRun.runId(), goodRun.stopId());
        assertThat(proximityNotifiedAt).as("앞 회차가 예외를 던져도 뒤 회차는 정상 판정돼야 한다").isNotNull();
    }

    @Test
    void 목표12_락을_다른_인스턴스가_쥐고_있으면_판정을_건너뛴다() {
        insertLock(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long runId = fullyWiredMovingRun(fx, academyId, busId, "근접학생락", "근접학부모락").runId();

        scheduler.judgeMovingRuns();

        verify(proximityNotificationService, never()).judgeOne(eq(runId), anyLong());
    }

    @Test
    void 목표13_락_보유_인스턴스가_죽어도_다음_틱에_이어받는다() {
        insertLock(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long runId = fullyWiredMovingRun(fx, academyId, busId, "근접학생회수", "근접학부모회수").runId();

        scheduler.judgeMovingRuns();

        verify(proximityNotificationService, times(1)).judgeOne(eq(runId), eq(academyId));
    }

    /**
     * "다른 인스턴스가 이 락을 쥐고 있다" 를 흉내내는 shedlock 행 — {@code lock_until} 이 미래면
     * 목표12(건너뜀), 과거면 목표13(만료돼 이어받음)이다.
     *
     * <p>{@code shedlock} 의 {@code lock_until}·{@code locked_at} 은 {@code TIMESTAMP} 로,
     * 시간대 정보가 없는 컬럼이다(V4 마이그레이션). 이 프로젝트는 그 컬럼에 항상 <b>UTC 벽시계
     * 숫자</b>를 저장하는 관례를 쓴다({@code JdbcTemplateLockProvider} 가 SQL 쪽에서
     * {@code timezone('utc', CURRENT_TIMESTAMP)} 로 채우는 것과 같다). {@code Timestamp.from(Instant)}
     * 로 바인딩하면 드라이버가 <b>JVM 기본 시간대(KST, UTC+9)로 벽시계 숫자를 다시 계산</b>해
     * 그 관례를 어기고, 저장된 값이 실제보다 9시간 앞선 것처럼 보여 만료 판정의
     * {@code lock_until <= now} 가 항상 거짓이 된다 — 목표13 이 이 경로로 늘 실패했던 원인이다.
     * {@code Timestamp.valueOf(LocalDateTime)} 는 그 재계산을 거치지 않고 벽시계 숫자를 그대로 옮긴다.
     *
     * <p>{@code ON CONFLICT (name) DO UPDATE} 를 쓰는 이유 — {@code tearDown()} 이 행을 지우지
     * 않고 과거 시각으로만 갱신하므로({@code JdbcTemplateLockProvider} 의 값싼 갱신 경로를 지키기
     * 위함) 이 시험이 두 번째로 돌 때는 행이 이미 있다. 단순 {@code INSERT} 는 그때 기본키 충돌로
     * 실패한다.
     */
    private void insertLock(OffsetDateTime lockUntil) {
        OffsetDateTime lockedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60);
        jdbcTemplate.update(
                "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (name) DO UPDATE SET lock_until = excluded.lock_until, "
                        + "locked_at = excluded.locked_at, locked_by = excluded.locked_by",
                "proximity-notification", java.sql.Timestamp.valueOf(lockUntil.toLocalDateTime()),
                java.sql.Timestamp.valueOf(lockedAt.toLocalDateTime()), "다른-인스턴스-시험");
    }

    private void writePosition(long runId, String lat, String lng) {
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"2030-04-01T00:00:00Z","receivedAt":"2030-04-01T00:00:01Z","currentStopName":"흉내"}
                """.formatted(lat, lng).strip();
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);
    }
}
