package src.backend.notification.repository;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;

/**
 * 수신자 본인의 알림함 — 목록 · 미읽음 수 · 읽음 처리 · 수신 확인(API_SPEC §3.12·§3.13). {@link NotificationLogRepository} 가 상속한다.
 */
@NoRepositoryBean
public interface NotificationLogInboxQueries extends Repository<NotificationLog, Long> {

    /**
     * 읽음 시각을 <b>최초 1회만</b> 남긴다(NTF-08, API_SPEC §3.13) — 조건부 UPDATE 라 읽음 시각 한 컬럼만 쓴다. 엔티티를 고쳐 변경 감지로 쓰면 읽어 둔
     * 시점의 발송 상태까지 행 전체를 다시 써서, 그 사이 발송 쪽이 확정한 {@code sent} · 시도 횟수가 {@code pending} · 0 으로 되돌아간다.
     *
     * @return 1 = 처음 읽음, 0 = 이미 읽은 알림(재요청에도 첫 읽음 시각이 보존된다)
     */
    @AcademyScopeExempt(reason = "알림 읽음 처리 — 대상이 id 로 특정된 단건이고, 소유(수신 계정)와 학원 범위는 호출부가 이 UPDATE 전에 "
            + "판정한다")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.readAt = :now
             where n.id = :id and n.readAt is null
            """)
    int markRead(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 수신 확인(NTF-10)을 <b>최초 1회만</b> 남긴다 — {@link #markRead} 와 같은 이유로 확인 표시 두 컬럼만 쓰는 조건부 UPDATE 다.
     *
     * @return 1 = 처음 확인, 0 = 이미 확인된 알림
     */
    @AcademyScopeExempt(reason = "알림 수신 확인 — markRead 와 같은 축이다. 대상이 id 로 특정된 단건이고 소유 판정은 호출부가 먼저 한다")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.acked = true, n.ackedAt = :now
             where n.id = :id and n.acked = false
            """)
    int ack(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 알림 목록 조회(API_SPEC §3.12) — {@code type}·{@code unreadOnly} 가 선택이고
     * 보관 14일이 항상 걸린다.
     *
     * <p>{@code type} 은 {@code :type IS NULL OR ...} 형태를 쓴다({@code ExceptionReportRepository#search}
     * 와 같은 근거) — enum 파라미터는 null 이어도 Postgres 가 타입을 추론한다(같은 파일 재확인).
     * {@code unreadOnly} 는 boolean 이라 {@code :unreadOnly = false OR ...} 로 충분하다 — boolean
     * 파라미터는 항상 값이 있어 {@code OffsetDateTime} 과 같은 추론 실패가 재현되지 않는다.
     *
     * <p>{@code retentionFrom} 은 "필터 없음"을 널로 표현하지 않는다 — 14일은 이 목록의
     * <b>항상-켜진</b> 조건이라 선택 필터와 성격이 다르고, 호출부({@code NotificationQueryService})가
     * 이미 구체 시각을 계산해 넘긴다.
     *
     * <p>{@code academyId} 가 {@code null} 이면 학원 조건을 뺀다 — 소속 학원이 없는 메인 관리자뿐이며
     * ({@code AcademyScope.resolveListScope}), 그 계정 앞 알림(비상)은 사고 학원 앞으로 적재된다(BR-072).
     * 수신자 조건은 항상 걸린다.
     *
     * <p>정렬은 {@code created_at desc}(+ {@code id desc} 동점 결정)다 — {@code sent_at} 은 아직
     * 발송 전이거나(즉시 발송 창) 설정 off 로 건너뛴 행에서 널이라 정렬 축으로 쓰면 그 행들이
     * 순서 없이 흩어진다. {@code ERD notification_log(recipient_account_id, created_at desc)}
     * 인덱스가 가리키는 축과도 같다.
     */
    @Query("""
            select n from NotificationLog n
             where (:academyId is null or n.academyId = :academyId)
               and n.recipientAccountId = :accountId
               and (:type is null or n.type = :type)
               and (:unreadOnly = false or n.readAt is null)
               and n.createdAt >= :retentionFrom
             order by n.createdAt desc, n.id desc
            """)
    Page<NotificationLog> search(@Param("academyId") Long academyId, @Param("accountId") Long accountId,
            @Param("type") NotificationType type, @Param("unreadOnly") boolean unreadOnly,
            @Param("retentionFrom") OffsetDateTime retentionFrom, Pageable pageable);

    /**
     * 미읽음 배지(API_SPEC §3.12 {@code unread_count}) — {@code items[]} 에 걸리는 {@code type}·
     * {@code unreadOnly} 필터와 <b>무관하게</b> 항상 전체 미읽음 수다. 보관 14일만 함께 건다
     * (ERD {@code notification_log(recipient_account_id, created_at desc)} 인덱스 주석이 목록·배지를
     * 같은 축으로 묶어 뒀다) — 14일보다 오래된 미읽음까지 세면 배지가 목록에 없는 항목을 가리키게 된다.
     */
    @Query("""
            select count(n) from NotificationLog n
             where (:academyId is null or n.academyId = :academyId)
               and n.recipientAccountId = :accountId
               and n.readAt is null
               and n.createdAt >= :retentionFrom
            """)
    long countUnread(@Param("academyId") Long academyId, @Param("accountId") Long accountId,
            @Param("retentionFrom") OffsetDateTime retentionFrom);
}
