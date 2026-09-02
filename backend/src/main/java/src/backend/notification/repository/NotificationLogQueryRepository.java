package src.backend.notification.repository;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;

/**
 * 관계자 웹의 알림 로그 전수 조회(API_SPEC §5.17) 전용 읽기 접근.
 *
 * <p>{@link NotificationLogRepository} 와 별도 파일인 이유는 그 인터페이스가 아웃박스
 * 적재·회수·발송 상태 전이를 다루는 <b>T2 소유 파일</b>이기 때문이다(IMPLEMENTATION_PLAN Phase 12
 * 좌석표 §4 — "notification_log 을 쓰기하는 좌석은 T2 하나다. T3 은 읽기 전용이라 같은 파일을 고치지
 * 않는다"). 이 저장소 전체에 한 엔티티가 리포지토리 인터페이스를 2개 갖는 선례는 없으나, Spring Data
 * JPA 는 같은 엔티티에 여러 리포지토리를 두는 것을 기술적으로 지원하고, 두 파일이 갈리면 T2 의 전이
 * 메서드 추가와 이 파일의 조회 메서드 추가가 git 병합에서 서로 부딪힐 자리가 부재해진다.
 */
public interface NotificationLogQueryRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * 알림 로그 목록(§5.17) — {@code type}·{@code acked} 는 선택적 필터이고 날짜 구간은 항상 구체값이다
     * ({@link src.backend.exception.repository.ExceptionReportRepository#search} 와 같은 근거 —
     * {@code OffsetDateTime} 단독 {@code IS NULL} 비교는 Postgres 파라미터 타입 추론이 실패한다).
     *
     * <p>{@code push_state} 조건을 두지 않는다 — 이 목록은 "전송 알림 전수 조회" 라 푸시 off 로
     * 막혀 {@code SKIPPED} 로 남은 행도 그대로 나와야 한다(§5.17 "푸시 off 로 차단된 건도 레코드로
     * 존치"). {@code sent_at} 이 비어 있을 수 있는 행({@code SKIPPED}·아직 안 보낸 {@code PENDING})은
     * {@code COALESCE(sent_at, created_at)} 으로 정렬·기간필터 모두를 대신한다 — 그렇지 않으면 그런
     * 행이 날짜 필터를 걸 때마다 조용히 빠진다.
     */
    @Query("""
            SELECT n FROM NotificationLog n
             WHERE n.academyId = :academyId
               AND (:type IS NULL OR n.type = :type)
               AND (:acked IS NULL OR n.acked = :acked)
               AND COALESCE(n.sentAt, n.createdAt) >= :from
               AND COALESCE(n.sentAt, n.createdAt) < :to
             ORDER BY COALESCE(n.sentAt, n.createdAt) DESC, n.id DESC
            """)
    Page<NotificationLog> search(@Param("academyId") Long academyId, @Param("type") NotificationType type,
            @Param("acked") Boolean acked, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * 미확인 배지(§5.17 {@code unacked_count}, ERD §5 부분 인덱스 {@code notification_log(academy_id,
     * acked) WHERE acked = false}) — 목록의 페이지·필터와 무관하게 <b>학원 전체</b>에서 센다. 페이지
     * 안에서만 세면 필터를 걸 때마다 배지 값이 달라져 "확인 안 한 것이 몇 건인가" 라는 원래 의미를
     * 잃는다.
     */
    long countByAcademyIdAndAckedFalse(Long academyId);
}
