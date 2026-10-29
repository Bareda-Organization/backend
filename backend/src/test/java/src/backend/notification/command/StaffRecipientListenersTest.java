package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.notification.controller.StaffNotificationFixtures;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.event.IntentChangedEvent;

/**
 * 관계자에게 알리는 리스너들의 수신자 집합과 중복 방지 키 — 그 학원의 재직 관계자만 받고(퇴사자 제외), 키는 사건 · 수신자 · 시각을 모두 담아
 * 같은 사건의 재전달은 건너뛰고 다른 수신자의 알림은 서로 막지 않는다. 실제 DB 의 관계자 조회를 거치므로 재직 판정이 바뀌면 여기서 드러난다.
 */
@SpringBootTest
@Transactional
class StaffRecipientListenersTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private static final long STUDENT_ID = 1L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    private long academyId;

    private long staff;

    private long resignedStaff;

    /** 학원당 재직 관계자는 1명이라(uk_academy_staff_academy_active) 퇴사자를 먼저 만들어 퇴사시킨 뒤 재직 1명을 둔다. */
    @BeforeEach
    void 퇴사_한_명과_재직_한_명의_학원을_만든다() {
        StaffNotificationFixtures fx = StaffNotificationFixtures.of(applicationContext);
        academyId = fx.academy();
        resignedStaff = fx.staffAccount(academyId, "퇴사자");
        academyStaffRepository.findByAccountId(resignedStaff).orElseThrow().resign();
        academyStaffRepository.flush();
        staff = fx.staffAccount(academyId, "재직");
    }

    @Test
    void 탑승_의사_변경은_재직_관계자_전원에게_수신자별_키로_적재된다() {
        publisher.publishEvent(new IntentChangedEvent(88L, academyId, STUDENT_ID, false, AT));

        assertThat(rows("intent_changed")).containsExactlyEntriesOf(Map.of(
                staff, "intent_changed:88:" + staff + ":" + AT));
    }

    @Test
    void 승인_대기_접수는_재직_관계자_전원에게_수신자별_키로_적재된다() {
        publisher.publishEvent(new ApprovalRequestedEvent(77L, academyId, 88L, STUDENT_ID, AT));

        assertThat(rows("approval_requested")).containsExactlyEntriesOf(Map.of(
                staff, "approval_requested:88:" + staff + ":" + AT));
    }

    @Test
    void 미탑승_확정은_재직_관계자_전원에게_학생까지_담은_키로_적재된다() {
        publisher.publishEvent(new AbsentRecordedEvent(88L, academyId, STUDENT_ID, AT));

        assertThat(rows("absent")).containsExactlyEntriesOf(Map.of(
                staff, "absent:88:staff:" + staff + ":student:" + STUDENT_ID + ":" + AT));
    }

    @Test
    void 미승차_격상은_재직_관계자_전원에게_사건_번호_키로_적재된다() {
        publisher.publishEvent(new NoShowEscalatedEvent(academyId, 55L, 88L, STUDENT_ID, AT));

        assertThat(rows("no_show_escalated")).containsExactlyEntriesOf(Map.of(
                staff, "no_show_escalated:55:" + staff + ":" + AT));
    }

    @Test
    void 예외_보고는_재직_관계자_전원에게_보고_번호_키로_적재된다() {
        publisher.publishEvent(new ExceptionReportedEvent(66L, academyId, 88L, AT));

        assertThat(rows("exception_reported")).containsExactlyEntriesOf(Map.of(
                staff, "exception_reported:66:" + staff + ":" + AT));
    }

    @Test
    void 비상_신고와_취소는_재직_관계자와_메인_관리자에게_수신자별_키로_적재된다() {
        publisher.publishEvent(new EmergencyRaisedEvent(44L, academyId, "시험학원", 88L, "01호차", EmergencyType.ACCIDENT,
                new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010-0000-0000"),
                new EmergencyRaisedEvent.Position(new BigDecimal("37.5"), new BigDecimal("127.0")), 3, AT));
        publisher.publishEvent(new EmergencyCanceledEvent(44L, academyId, 88L, "01호차", AT));

        List<Long> admins = jdbcTemplate.queryForList(
                "SELECT id FROM account WHERE role = 'system_admin' AND status IN ('active', 'blocked')", Long.class);
        assertThat(admins).as("시드에 메인 관리자가 있어야 이 시험이 admin 갈래를 본다").isNotEmpty();
        assertThat(rows("emergency").keySet()).containsExactlyInAnyOrderElementsOf(합집합(staff, admins));
        assertThat(rows("emergency")).containsEntry(staff, "emergency:44:" + staff);
        assertThat(rows("emergency_canceled").keySet()).containsExactlyInAnyOrderElementsOf(합집합(staff, admins));
        assertThat(rows("emergency_canceled")).containsEntry(staff, "emergency_canceled:44:" + staff);
        assertThat(rows("emergency")).doesNotContainKey(resignedStaff);
    }

    @Test
    void 퇴사한_관계자에게는_어느_사건도_적재되지_않는다() {
        publisher.publishEvent(new ExceptionReportedEvent(66L, academyId, 88L, AT));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_log WHERE recipient_account_id = ?",
                Integer.class, resignedStaff)).isZero();
    }

    /** 수신 계정 → 중복 방지 키. 이 시험이 만든 학원의 행만 본다. */
    private Map<Long, String> rows(String type) {
        Map<Long, String> byRecipient = new java.util.HashMap<>();
        jdbcTemplate.query("SELECT recipient_account_id, dedup_key FROM notification_log WHERE academy_id = ? AND type = ?",
                rs -> {
                    byRecipient.put(rs.getLong(1), rs.getString(2));
                }, academyId, type);
        return byRecipient;
    }

    private static List<Long> 합집합(long staff, List<Long> admins) {
        List<Long> all = new java.util.ArrayList<>(List.of(staff));
        all.addAll(admins);
        return all;
    }
}
