package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.global.common.enums.Role;
import src.backend.run.dto.DelayNoticeRecipient;
import src.backend.run.entity.DelayReason;
import src.backend.run.event.DelayRequestedEvent;

/**
 * 지연 신고·비상 접수·비상 취소의 알림 적재 SQL 문장 수는 수신자 수와 무관하다(BR-354) — 운행 시작만 묶고({@code
 * RunStartedNotificationStatementCountTest}, R46 T-6) 이 형제 호출부를 건별 {@code append} 로 두면, 지연 신고는 한 요청에서 수십~
 * 백여 번의 INSERT 를 내고 비상 접수는 {@code client_key} 자문 잠금을 쥔 채 관계자·메인 관리자 수만큼 문장을 낸다.
 *
 * <p>문장 수는 트랜잭션 안에서 이벤트 발행 전·후의 Hibernate 통계 차이로 센다(커밋 뒤 비동기 발송의 문장은 세지 않는다).
 * 수신자는 2명과 10명 두 번 재서 차이를 본다 — 알림 로그에는 계정 FK 가 없어 지연 신고는 이벤트가 나르는 수신자 목록을 그대로
 * 만들어 넣는다.
 */
@SpringBootTest
class RecipientFanOutStatementCountTest {

    /** 두 측정의 차이 허용치 — 다른 스레드(스케줄러)가 같은 통계에 얹는 문장 몇 건의 잡음이다. */
    private static final long NOISE_TOLERANCE = 2;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final OffsetDateTime AT = OffsetDateTime.of(2030, 4, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    private final List<Long> academyIds = new ArrayList<>();

    private final List<Long> adminIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
        for (Long adminId : adminIds) {
            jdbcTemplate.update("DELETE FROM account WHERE id = ?", adminId);
        }
        adminIds.clear();
    }

    @Test
    @DisplayName("BR-354 — 지연 신고 알림 적재의 SQL 문장 수는 관계자·학부모·학생 수와 무관하다")
    void 지연_신고_적재의_문장_수는_수신자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(recipients -> {
            long academyId = academyWithStaff(0);
            return new DelayRequestedEvent(runIdOf(academyId), academyId, 15, DelayReason.TRAFFIC, null, AT,
                    recipientsOf(recipients, 1_000), recipientsOf(recipients, 2_000), recipientsOf(recipients, 3_000));
        });
    }

    @Test
    @DisplayName("BR-354 — 비상 접수 알림 적재의 SQL 문장 수는 메인 관리자 수와 무관하다")
    void 비상_접수_적재의_문장_수는_수신자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(admins -> {
            addMainAdmins(admins);
            long academyId = academyWithStaff(1);
            return new EmergencyRaisedEvent(1L + SEQUENCE.incrementAndGet(), academyId, "시험학원", 1L, "1호차",
                    EmergencyType.ACCIDENT, new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010-0000-0000"),
                    new EmergencyRaisedEvent.Position(null, null), 0, AT);
        });
    }

    @Test
    @DisplayName("BR-354 — 비상 취소 알림 적재의 SQL 문장 수는 메인 관리자 수와 무관하다")
    void 비상_취소_적재의_문장_수는_수신자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(admins -> {
            addMainAdmins(admins);
            long academyId = academyWithStaff(1);
            return new EmergencyCanceledEvent(1L + SEQUENCE.incrementAndGet(), academyId, 1L, "1호차", AT);
        });
    }

    /** 수신자 2명·10명으로 이벤트를 만들어 각각 발행하고, 앞 측정의 비동기 발송이 끝난 뒤 뒤 측정을 한다. */
    private void assertStatementCountIndependentOfRecipients(IntFunction<Object> eventWithRecipients) {
        long few = statementsWhilePublishing(eventWithRecipients.apply(2));
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingRows() == 0);
        long many = statementsWhilePublishing(eventWithRecipients.apply(10));

        assertThat(many - few).as("수신자 2명 %d문장 · 10명 %d문장 — 수신자가 늘면 문장이 늘면 안 된다", few, many)
                .isLessThanOrEqualTo(NOISE_TOLERANCE);
    }

    private long statementsWhilePublishing(Object event) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long[] counted = new long[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long before = statistics.getPrepareStatementCount();
            eventPublisher.publishEvent(event);
            counted[0] = statistics.getPrepareStatementCount() - before;
        });
        return counted[0];
    }

    private int pendingRows() {
        return academyIds.isEmpty() ? 0 : jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE push_state = 'pending' AND academy_id = ANY(?)",
                Integer.class, (Object) academyIds.toArray(new Long[0]));
    }

    /**
     * 메인 관리자 {@code count} 명을 더 만든다(재직 상태) — 비상 알림은 학원 재직 관계자 전원 + 메인 관리자 전원이 수신자이고, 학원당
     * 재직 관계자는 스키마가 1명으로 막아({@code uk_academy_staff_academy_active}) 수신자가 늘어나는 쪽은 메인 관리자다.
     */
    private void addMainAdmins(int count) {
        for (int i = 0; i < count; i++) {
            Account admin = accountRepository.save(Account.forSignup(null,
                    "관리자" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime(), "x", "메인관리자" + i, "010-0000-0000",
                    null, Role.SYSTEM_ADMIN));
            jdbcTemplate.update("UPDATE account SET status = 'active' WHERE id = ?", admin.getId());
            adminIds.add(admin.getId());
        }
    }

    /** 재직 관계자 {@code staff} 명이 있는 학원 — 비상 알림의 관계자 수신자는 이 재직 목록에서 나온다. */
    private long academyWithStaff(int staff) {
        long academyId = academyRepository.save(Academy.register("FAN" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime(),
                "수신자시험학원", "서울", null, null)).getId();
        academyIds.add(academyId);
        for (int i = 0; i < staff; i++) {
            Account account = accountRepository.save(Account.forSignup(academyId,
                    "팬아웃" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime(), "x", "관계자" + i, "010-0000-0000", null,
                    Role.STAFF));
            academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        }
        return academyId;
    }

    private static long runIdOf(long academyId) {
        return 900_000_000L + academyId;
    }

    /** 계정·학생이 실재하지 않는 수신자 목록 — 알림 로그에는 FK 가 없고 문구는 학생을 못 찾으면 관계자 문구로 대신한다. */
    private static List<DelayNoticeRecipient> recipientsOf(int count, long idBase) {
        return IntStream.range(0, count)
                .mapToObj(i -> new DelayNoticeRecipient(idBase + i + SEQUENCE.incrementAndGet() * 100_000L, "수신자" + i,
                        idBase + i))
                .toList();
    }
}
