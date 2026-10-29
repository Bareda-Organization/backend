package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.Role;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;

/**
 * 관계자 웹의 알림 로그 조회(API_SPEC §5.17) — 목록 · 묶어 보기 · 미확인 배지. {@link NotificationLogRepository} 가 상속한다.
 *
 * <p>조회 메서드({@code searchForStaffLog}·{@code countUnackedForStaffLog})는 §5.17 전용이다. 이름에 {@code ForStaffLog} 를 붙인 이유는 한 저장소가 전이 메서드와 조회
 * 메서드를 함께 갖게 되면서, 양쪽 담당이 서로 다른 뜻으로 같은 이름을 지어 충돌하는 것이 실제 위험이기 때문이다(Ruling 222) — 아웃박스 전이 메서드는 전부
 * 단건({@code id} 로 특정)이라 이름이 겹칠 일이 없지만 조회는 그렇지 않다.
 */
@NoRepositoryBean
public interface NotificationLogStaffLogQueries extends Repository<NotificationLog, Long> {

    /**
     * 알림 로그 목록(§5.17) — {@code type}·{@code acked} 는 선택적 필터이고 날짜 구간은 항상 구체값이다
     * ({@link src.backend.exception.repository.ExceptionReportRepository#search} 와 같은 근거 —
     * {@code OffsetDateTime} 단독 {@code IS NULL} 비교는 Postgres 파라미터 타입 추론이 실패한다).
     *
     * <p>{@code push_state} 조건을 두지 않는다 — 이 목록은 "전송 알림 전수 조회" 라 푸시 off 로
     * 막혀 {@code SKIPPED} 로 남은 행도 그대로 나와야 한다(§5.17 "푸시 off 로 차단된 건도 레코드로
     * 존치"). {@code sent_at} 이 비어 있을 수 있는 행({@code SKIPPED}·아직 안 보낸 {@code PENDING})은
     * {@code COALESCE(sent_at, created_at)} 으로 정렬·기간필터 모두를 대신한다 — 그렇지 않으면 그런
     * 행이 날짜 필터를 걸 때마다 조용히 빠진다. 그 식 그대로 식 인덱스
     * ({@code ix_notification_log_academy_shown})가 있다(BR-090).
     *
     * <p>{@code acked} 를 주면 수신 확인 대상 종류({@code importantTypes})로도 좁힌다 — 대상 밖 종류는 영원히
     * {@code acked=false} 라, 섞이면 "확인 안 한 중요 통지" 목록이 배지({@link #countUnackedForStaffLog})와
     * 다른 집합이 된다(NTF-10, BR-071).
     */
    @Query("""
            SELECT n FROM NotificationLog n
             WHERE n.academyId = :academyId
               AND (:type IS NULL OR n.type = :type)
               AND (:acked IS NULL OR (n.acked = :acked AND n.type IN :importantTypes))
               AND (:recipientRole IS NULL OR n.recipientRole = :recipientRole)
               AND COALESCE(n.sentAt, n.createdAt) >= :from
               AND COALESCE(n.sentAt, n.createdAt) < :to
             ORDER BY COALESCE(n.sentAt, n.createdAt) DESC, n.id DESC
            """)
    Page<NotificationLog> searchForStaffLog(@Param("academyId") Long academyId, @Param("type") NotificationType type,
            @Param("acked") Boolean acked, @Param("importantTypes") Set<NotificationType> importantTypes,
            @Param("recipientRole") Role recipientRole, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, Pageable pageable);

    /**
     * 묶어 보기({@code group=true}, §5.17, Ruling 813)의 공통 조건 조각 — 묶음 쪽 조회와 묶음 수 조회가 <b>같은</b> 조건을 쓴다
     * (둘이 갈리면 {@code total_count} 와 쪽 내용이 어긋난다). 묶음 키는 같은 {@code type} · {@code run_id} · {@code body} · 적재 시각
     * ({@code created_at}) 초 단위이고, 한 사건의 행은 한 트랜잭션에서 같은 시각 값으로 적재된다. 날짜 구간은
     * {@link #searchForStaffLog} 와 같이 {@code COALESCE(sent_at, created_at)} 기준이다. {@code CAST(:x AS ...) IS NULL} 은 Postgres 가 널
     * 파라미터 타입을 추론하지 못하는 자리를 피한다.
     */
    String STAFF_LOG_GROUP_FILTER = """
            FROM notification_log n
            WHERE n.academy_id = :academyId
              AND (CAST(:type AS text) IS NULL OR n.type = CAST(:type AS text))
              AND (CAST(:recipientRole AS text) IS NULL OR n.recipient_role = CAST(:recipientRole AS text))
              AND COALESCE(n.sent_at, n.created_at) >= :from
              AND COALESCE(n.sent_at, n.created_at) < :to
            GROUP BY n.type, n.run_id, n.body, date_trunc('second', n.created_at)
            HAVING (CAST(:acked AS boolean) IS NULL OR SUM(CASE WHEN n.acked = CAST(:acked AS boolean)
                        AND n.type IN (:importantTypes) THEN 1 ELSE 0 END) > 0)
            """;

    /**
     * 묶음 한 쪽(§5.17 {@code group=true}) — 최근 묶음부터 {@code limit} 개를 {@code offset} 부터. {@code acked} 를 주면 묶음 안에 그
     * 상태 행이 하나라도 있는 묶음을 싣는다. 수신자 앞 3명은 적재 순으로 {@code 이름 + 구분자 + 역할} 을 이어 한 칸에 돌려준다.
     * 학원 조건을 쿼리에 고정한다. 모든 행이 쪽과 무관한 전체 묶음 수({@code totalGroups})를 함께 싣는다 — 같은 집계를 개수용으로 한 번 더 돌리지 않는다.
     * 쪽 범위를 벗어나 행이 0 개이면 이 값을 읽을 행이 없어 {@link #countGroupsForStaffLog} 로 센다.
     */
    @Query(nativeQuery = true, value = """
            SELECT n.type AS "type", n.run_id AS "runId", n.body AS "body",
                   CAST(EXTRACT(EPOCH FROM date_trunc('second', n.created_at)) AS bigint) AS "bucketSecond",
                   CAST(EXTRACT(EPOCH FROM MAX(COALESCE(n.sent_at, n.created_at))) * 1000000 AS bigint) AS "sentAtMicros",
                   MAX(n.bus_no) AS "busNo",
                   COUNT(*) AS "recipientCount",
                   SUM(CASE WHEN n.acked THEN 1 ELSE 0 END) AS "ackedCount",
                   array_to_string((array_agg(n.recipient_name || chr(31) || n.recipient_role ORDER BY n.id))[1:3],
                                   chr(30)) AS "firstRecipients",
                   COUNT(*) OVER () AS "totalGroups"
            """ + STAFF_LOG_GROUP_FILTER + """
            ORDER BY MAX(COALESCE(n.sent_at, n.created_at)) DESC, MAX(n.id) DESC
            LIMIT :limit OFFSET :offset
            """)
    List<StaffLogGroup> searchGroupsForStaffLog(@Param("academyId") Long academyId, @Param("type") String type,
            @Param("recipientRole") String recipientRole, @Param("acked") Boolean acked,
            @Param("importantTypes") Collection<String> importantTypes, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("limit") int limit, @Param("offset") long offset);

    /** 묶음 수(§5.17 {@code group=true} 의 {@code total_count}) — {@link #searchGroupsForStaffLog} 와 같은 조건 조각을 쓴다. 빈 쪽에서만 부른다. */
    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM (SELECT 1 " + STAFF_LOG_GROUP_FILTER + ") g")
    long countGroupsForStaffLog(@Param("academyId") Long academyId, @Param("type") String type,
            @Param("recipientRole") String recipientRole, @Param("acked") Boolean acked,
            @Param("importantTypes") Collection<String> importantTypes, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** {@link #searchGroupsForStaffLog} 의 한 행 — 묶음 키 구성 값과 집계(시각은 마이크로초 에포크 정수로 받는다). */
    interface StaffLogGroup {

        String getType();

        Long getRunId();

        String getBody();

        long getBucketSecond();

        long getSentAtMicros();

        String getBusNo();

        long getRecipientCount();

        long getAckedCount();

        String getFirstRecipients();

        long getTotalGroups();
    }

    /**
     * 미확인 배지(§5.17 {@code unacked_count}, ERD §5 부분 인덱스 {@code notification_log(academy_id,
     * acked) WHERE acked = false}) — 목록의 페이지·필터와 무관하게 <b>학원 전체</b>에서 센다. 페이지
     * 안에서만 세면 필터를 걸 때마다 배지 값이 달라져 "확인 안 한 것이 몇 건인가" 라는 원래 의미를
     * 잃는다.
     *
     * <p>수신 확인 대상 3종({@link NotificationType#IMPORTANT_FOR_ACK}, FEATURE_SPEC NTF-10)만 센다 — 목록의
     * {@code acked} 필터도 같은 3종으로 좁히고, 부분 인덱스({@code ix_notification_log_academy_unacked})의
     * 조건도 같은 3종이다(BR-090).
     */
    @Query("""
            SELECT COUNT(n) FROM NotificationLog n
             WHERE n.academyId = :academyId
               AND n.acked = false
               AND n.type IN :importantTypes
            """)
    long countUnackedForStaffLog(@Param("academyId") Long academyId,
            @Param("importantTypes") Set<NotificationType> importantTypes);
}
