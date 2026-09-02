package src.backend.exception.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 미승차 에스컬레이션 폴링({@link NoShowEscalationScheduler#escalateDueNoShowCases}) 수준의 검증
 * (Phase 11 T1 목표 1) — {@code ChangeRequestAutoRejectionSchedulerTest} 와 같은 구조다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 스케줄러가 건마다 별도 빈({@link NoShowEscalationPersistence}
 * 대응, {@code exception.command} 패키지)의 {@code @Transactional} 메서드를 호출하므로, 테스트 스레드에
 * 묶인 트랜잭션 롤백은 그 트랜잭션이 커밋한 것을 되돌리지 못한다({@code ChangeRequestAutoRejectionSchedulerTest}
 * 와 같은 근거). 뒷정리는 {@link NoShowEscalationFixtures#ACADEMY_NAME} 로 표시된 행을 직접 지운다.
 */
@SpringBootTest
class NoShowEscalationSchedulerTest {

    @Autowired
    private NoShowEscalationScheduler scheduler;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private NoShowCaseRepository noShowCaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private NoShowEscalationFixtures fixtures;

    private OffsetDateTime now;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2032-04-01T03:00:00Z"); // 2032-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void setUp() {
        fixtures = new NoShowEscalationFixtures(academyRepository, academyStaffRepository, busRepository,
                studentRepository, stopRepository, accountRepository, runRepository, runRiderRepository,
                noShowCaseRepository);
        now = OffsetDateTime.now(clock);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpMarkedRows();
    }

    /**
     * {@link NoShowEscalationFixtures#ACADEMY_NAME} 로 표시된 행만 지운다 — {@code run} 삭제가
     * {@code run_rider}·{@code no_show_case} 를 이미 연쇄 삭제한다(V1 스키마, {@code ON DELETE CASCADE}).
     * {@code notification_log}·{@code academy_staff} 는 FK 연쇄 대상이 아니라 별도로 지운다.
     */
    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + NoShowEscalationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + NoShowEscalationFixtures.ACADEMY_NAME + "'");
    }

    /** 학원·버스·정류장·학생·회차·탑승자까지 갖춘 기본 시나리오 하나 — 각 시험이 케이스만 덧붙인다. */
    private long[] baseScenario() {
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId);
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long runId = fixtures.run(academyId, busId, now.plusHours(2));
        long riderId = fixtures.runRider(runId, studentId, stopId);
        return new long[] { academyId, staffAccountId, riderId };
    }

    @Test
    @DisplayName("목표1 — 대기가 만료된 케이스를 폴링이 에스컬레이션하고 관계자 알림을 남긴다")
    void 대기가_만료된_케이스를_폴링이_에스컬레이션한다() {
        long[] s = baseScenario();
        long staffAccountId = s[1];
        long riderId = s[2];
        long caseId = fixtures.noShowCase(riderId, now.minusMinutes(10), now.minusMinutes(1));

        scheduler.escalateDueNoShowCases();

        NoShowCase found = noShowCaseRepository.findById(caseId).orElseThrow();
        assertThat(found.getEscalatedAt()).as("①escalated_at 이 채워져야 한다").isNotNull();

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'no_show_escalated'",
                Integer.class, staffAccountId);
        assertThat(notificationCount).as("②관계자에게 no_show_escalated 알림이 남아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("목표1 경계 — 만료가 지금 이 순간이면(같음) 폴링이 즉시 에스컬레이션한다")
    void 만료가_지금과_같아도_폴링이_에스컬레이션한다() {
        long[] s = baseScenario();
        long riderId = s[2];
        long caseId = fixtures.noShowCase(riderId, now.minusMinutes(10), now);

        scheduler.escalateDueNoShowCases();

        NoShowCase found = noShowCaseRepository.findById(caseId).orElseThrow();
        assertThat(found.getEscalatedAt()).as("expires_at == now 는 이미 도래한 것으로 포함해야 한다").isNotNull();
    }

    @Test
    @DisplayName("목표6 — 이미 에스컬레이션됐거나 해소된 케이스는 폴링 질의에 다시 걸리지 않는다")
    void 이미_처리된_케이스는_다시_걸리지_않는다() {
        long[] alreadyEscalated = baseScenario();
        long escalatedCaseId = fixtures.noShowCase(alreadyEscalated[2], now.minusMinutes(10), now.minusMinutes(1));
        jdbcTemplate.update("UPDATE no_show_case SET escalated_at = ? WHERE id = ?", now.minusMinutes(5),
                escalatedCaseId);

        long[] alreadyResolved = baseScenario();
        long resolvedCaseId = fixtures.noShowCase(alreadyResolved[2], now.minusMinutes(10), now.minusMinutes(1));
        jdbcTemplate.update("UPDATE no_show_case SET resolved_at = ? WHERE id = ?", now.minusMinutes(2),
                resolvedCaseId);

        scheduler.escalateDueNoShowCases();

        NoShowCase escalated = noShowCaseRepository.findById(escalatedCaseId).orElseThrow();
        assertThat(escalated.getEscalatedAt()).as("이미 에스컬레이션된 시각이 덮어써지면 안 된다")
                .isEqualTo(now.minusMinutes(5));

        NoShowCase resolved = noShowCaseRepository.findById(resolvedCaseId).orElseThrow();
        assertThat(resolved.getEscalatedAt()).as("연락이 응답으로 해소됐으면 에스컬레이션되면 안 된다").isNull();
    }

    @Test
    @DisplayName("목표5 — 한 건의 실패가 다른 건을 막지 않는다")
    void 한_건의_실패가_다른_건을_막지_않는다() {
        long[] good = baseScenario();
        long goodRiderId = good[2];
        long goodCaseId = fixtures.noShowCase(goodRiderId, now.minusMinutes(10), now.minusMinutes(1));

        long[] bad = baseScenario();
        long badStaffAccountId = bad[1];
        long badRiderId = bad[2];
        long badCaseId = fixtures.noShowCase(badRiderId, now.minusMinutes(10), now.minusMinutes(1));

        // 이 케이스가 에스컬레이션되며 만들 dedup_key 를 미리 점유해, 알림 적재에서 DUPLICATE_NOTIFICATION 이
        // 나게 만든다 — 그 케이스의 트랜잭션 전체(조건부 UPDATE 포함)가 롤백된다.
        String collidingDedupKey = "no_show_escalated:" + badCaseId + ":" + badStaffAccountId + ":" + now;
        jdbcTemplate.update(
                "INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, "
                        + "type, title, body, dedup_key) VALUES (?, ?, '선점', 'staff', 'no_show_escalated', "
                        + "'선점', '선점', ?)",
                bad[0], badStaffAccountId, collidingDedupKey);

        scheduler.escalateDueNoShowCases();

        NoShowCase goodCase = noShowCaseRepository.findById(goodCaseId).orElseThrow();
        assertThat(goodCase.getEscalatedAt()).as("옆 건이 실패해도 이 건은 에스컬레이션돼야 한다").isNotNull();

        NoShowCase badCase = noShowCaseRepository.findById(badCaseId).orElseThrow();
        assertThat(badCase.getEscalatedAt()).as("알림 적재 실패로 이 건의 트랜잭션(조건부 UPDATE 포함)은 롤백돼야 한다")
                .isNull();
    }

    /**
     * Phase 11 T1 fix1 §2 — {@code findDueForEscalation} 의 {@code escalatedAt IS NULL} 필터가
     * 없어도 뒤쪽 조건부 UPDATE 가 정합성은 지키지만, 그 필터 자체의 목적(배치 상한 보호)은 이
     * 시험 이전엔 검증되지 않았다(리뷰 3회차 변형이 살아남음). 이미 처리된 케이스를 배치 상한만큼
     * 쌓아 두고, 그보다 늦게 만료된 새 케이스가 같은 틱에서 밀리지 않는지로 그 필터의 존재 이유를
     * 직접 확인한다.
     */
    @Test
    @DisplayName("목표1 — 이미 처리된 케이스가 배치 상한만큼 쌓여 있어도 새로 만료된 케이스는 이번 틱에서 밀리지 않는다")
    void 이미_처리된_케이스가_배치를_채워도_새_케이스는_밀리지_않는다() {
        long academyId = fixtures.academy();
        fixtures.staffAccount(academyId);
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId);

        // 배치 상한(NoShowEscalationScheduler.BATCH_SIZE)만큼 "이미 처리된" 케이스를 만든다 —
        // expires_at 을 아주 오래전으로 둬서 ORDER BY expires_at ASC 정렬에서 항상 앞선다.
        for (int i = 0; i < NoShowEscalationScheduler.BATCH_SIZE; i++) {
            long studentId = fixtures.student(academyId, "이미처리" + i);
            // uk_run_bus_date_direction_depart(bus_id, service_date, direction, depart_time) 유니크라
            // 회차마다 depart_time 을 달리한다.
            long runId = fixtures.run(academyId, busId, now.plusHours(2).plusMinutes(i));
            long riderId = fixtures.runRider(runId, studentId, stopId);
            long caseId = fixtures.noShowCase(riderId, now.minusDays(1), now.minusDays(1));
            jdbcTemplate.update("UPDATE no_show_case SET escalated_at = ? WHERE id = ?", now.minusHours(12), caseId);
        }

        // 방금 만료됐고 아직 처리되지 않은 케이스 — expires_at 이 위 오래된 것들보다 나중이라
        // 필터 없이 정렬 + LIMIT 만 걸면 앞선 행들에 밀려 이번 틱에서 빠질 수 있다.
        long dueStudentId = fixtures.student(academyId, "새로만료");
        long dueRunId = fixtures.run(academyId, busId, now.plusHours(3));
        long dueRiderId = fixtures.runRider(dueRunId, dueStudentId, stopId);
        long dueCaseId = fixtures.noShowCase(dueRiderId, now.minusMinutes(10), now.minusMinutes(1));

        scheduler.escalateDueNoShowCases();

        NoShowCase due = noShowCaseRepository.findById(dueCaseId).orElseThrow();
        assertThat(due.getEscalatedAt())
                .as("이미 처리된 케이스가 배치 상한(" + NoShowEscalationScheduler.BATCH_SIZE + "건)을 채워도, "
                        + "폴링 필터가 그것들을 조회에서 제외해야 새 케이스가 이번 틱에서 밀리지 않는다")
                .isNotNull();
    }
}
