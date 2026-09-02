package src.backend.notification.controller;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.util.ReflectionTestUtils;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.entity.PushState;
import src.backend.notification.repository.NotificationLogQueryRepository;

/**
 * 알림 로그 전수 조회(T3, Phase 12 goal 10·11) 시험이 쓰는 실제 행 — {@code ExceptionReportFixtures}
 * 와 같은 이유로 정상 경로의 팩토리로 쌓는다. {@code NotificationLogRepository}(T2 소유, 아웃박스
 * 적재·회수)가 아니라 {@link NotificationLogQueryRepository}(이 태스크 소유)로 저장하는 이유도 같다 —
 * 두 파일이 갈린 근거가 곧 여기서 어느 저장소를 써야 하는지의 근거다.
 *
 * <p>{@link NotificationLog#acked}·{@link NotificationLog#ackedAt}·{@link NotificationLog#readAt} 을
 * 채우는 실제 "확인 처리" 쓰기 경로는 T2 담당이고, 이 워크트리에는 아직 존재하지 않는다(격리
 * 워크트리 기준 실측 — grep 무결과, report-p12-t3.md 2항 참고). 그 경로가 아직 이 저장소에 없다는
 * 점이 {@link src.backend.request.command.ChangeRequestAutoRejectFixtures#pendingChangeRequest} 가
 * {@code deadlineAt} 을 채우는 근거와 같아, 같은 패턴({@code ReflectionTestUtils})으로 저장 전 필드를
 * 직접 채운다. 이렇게 만든 "이미 확인된" 행은 <b>구조적 시험 데이터</b>일 뿐, T2 의 실제 쓰기 경로를
 * 검증하는 것이 아니다 — 그 경계 시험은 T2 병합 이후로 미룬다.
 */
public class StaffNotificationFixtures {

    private static final String ACADEMY_NAME = "알림로그시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final NotificationLogQueryRepository notificationLogQueryRepository;

    public StaffNotificationFixtures(AcademyRepository academyRepository, AccountRepository accountRepository,
            AcademyStaffRepository academyStaffRepository,
            NotificationLogQueryRepository notificationLogQueryRepository) {
        this.academyRepository = academyRepository;
        this.accountRepository = accountRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.notificationLogQueryRepository = notificationLogQueryRepository;
    }

    public long academy() {
        String code = "P12T3" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    /** 학원 관계자 계정 1명(§5.17 조회 주체) — 반환값은 로그인 토큰에 실을 계정 id. */
    public long staffAccount(long academyId, String name) {
        Account account = accountRepository.save(Account.forSignup(academyId, "직원" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /** 발송 대기(PENDING) 행 — 아직 발송되지 않아 {@code sent_at} 이 비어 있다({@code created_at} 이 대신 쓰인다). */
    public long notification(long academyId, NotificationType type, String recipientName, Role recipientRole,
            String body, String busNo, OffsetDateTime createdAt) {
        return save(academyId, type, recipientName, recipientRole, body, busNo, createdAt, PushState.PENDING, null,
                false, null);
    }

    /** 발송 완료(SENT) 행 — {@code sent_at} 이 채워진다(§5.17 {@code date} 필터가 기준으로 삼는 시각). */
    public long sentNotification(long academyId, NotificationType type, String recipientName, Role recipientRole,
            String body, String busNo, OffsetDateTime createdAt, OffsetDateTime sentAt) {
        return save(academyId, type, recipientName, recipientRole, body, busNo, createdAt, PushState.SENT, sentAt,
                false, null);
    }

    /**
     * 이미 확인(acked) 처리된 행 — 클래스 javadoc 근거로 {@code ReflectionTestUtils} 로 직접 채운다.
     */
    public long ackedNotification(long academyId, NotificationType type, String recipientName, Role recipientRole,
            String body, String busNo, OffsetDateTime createdAt, OffsetDateTime sentAt, OffsetDateTime ackedAt) {
        return save(academyId, type, recipientName, recipientRole, body, busNo, createdAt, PushState.SENT, sentAt,
                true, ackedAt);
    }

    /**
     * 푸시 off 설정으로 발송이 건너뛰인(SKIPPED) 행 — §5.17 "전송 알림 전수 조회 — 푸시 off 로 차단된
     * 건도 레코드로 존치" 조건(goal 10)의 대상. {@code sent_at} 은 끝내 비어 있다.
     */
    public long skippedNotification(long academyId, NotificationType type, String recipientName, Role recipientRole,
            String body, String busNo, OffsetDateTime createdAt) {
        return save(academyId, type, recipientName, recipientRole, body, busNo, createdAt, PushState.SKIPPED, null,
                false, null);
    }

    private long save(long academyId, NotificationType type, String recipientName, Role recipientRole, String body,
            String busNo, OffsetDateTime createdAt, PushState pushState, OffsetDateTime sentAt, boolean acked,
            OffsetDateTime ackedAt) {
        long recipientAccountId = SEQUENCE.incrementAndGet();
        String dedupKey = "P12T3-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        NotificationLog log = NotificationLog.forOutbox(academyId, recipientAccountId, recipientName, recipientRole,
                type, "알림 제목", body, dedupKey, createdAt);
        if (busNo != null) {
            ReflectionTestUtils.setField(log, "busNo", busNo);
        }
        ReflectionTestUtils.setField(log, "pushState", pushState);
        if (sentAt != null) {
            ReflectionTestUtils.setField(log, "sentAt", sentAt);
        }
        if (acked) {
            ReflectionTestUtils.setField(log, "acked", true);
            ReflectionTestUtils.setField(log, "ackedAt", ackedAt);
            ReflectionTestUtils.setField(log, "readAt", ackedAt);
        }
        return notificationLogQueryRepository.save(log).getId();
    }
}
