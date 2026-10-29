package src.backend.location.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.location.entity.RunPosition;

/**
 * {@link RunPosition} 영속성 접근 — 적재(save) · Redis 장애 시 최신 행 대체 조회(BR-167) — 보존 정리는 일 단위 파티션 DROP 이라 여기 없다({@link src.backend.location.infrastructure.RunPositionPartitionManager}).
 */
public interface RunPositionRepository extends JpaRepository<RunPosition, Long> {

    /**
     * 회차별 최신 행 1건씩 — Redis 최신 좌표의 대체 재료(BR-167, TECH_DECISIONS §14.2). 여러 회차를 한 번에 읽어
     * 관제가 회차 수만큼 쿼리를 내지 않는다. 회차마다 인덱스 {@code ix_run_position_run_recorded (run_id, recorded_at DESC)} 의
     * 앞 1행만 읽는다(LATERAL + LIMIT 1) — {@code DISTINCT ON} 은 회차의 전 행을 정렬해 실측 18.0ms → 0.96ms.
     *
     * <p><b>{@code since} ≤ {@code recorded_at} &lt; {@code until} 구간을 반드시 준다</b>(DA-2, Ruling 899) — 구간이 없으면 파티션마다 회차
     * 인덱스를 한 번씩 찾아 일 파티션 수(보존 기간 + 미리 만든 7일)만큼 비용이 는다. 구간이 있으면 닿는 파티션 2개 안에서 끝난다.
     */
    @AcademyScopeExempt(reason = "Redis 최신 좌표의 대체 조회 — 호출자(RunPositionStore 의 소비자)가 이미 학원 범위로 "
            + "확인한 회차 id 만 넘기고, run_position 에는 academy_id 컬럼이 부재하다")
    @Query(value = """
            SELECT p.*
            FROM run r
            JOIN LATERAL (
                SELECT * FROM run_position
                WHERE run_id = r.id AND recorded_at >= :since AND recorded_at < :until
                ORDER BY recorded_at DESC, id DESC
                LIMIT 1
            ) p ON true
            WHERE r.id IN (:runIds)
            """, nativeQuery = true)
    List<RunPosition> findLatestByRunIdIn(@Param("runIds") Collection<Long> runIds, @Param("since") OffsetDateTime since,
            @Param("until") OffsetDateTime until);

    /**
     * {@link #findLatestByRunIdIn} 과 같되 <b>운행 중({@code moving}) 회차</b>만 — Redis 가 살아 있는데 값이 비어 있을 때(다시 시작한 Redis) 최신 좌표를 채우는 재료다. 구간은 위치가 낡았다고 보는
     * 기준이라 한 회차당 인덱스 앞 1행을 읽는 것은 같고, 대기 · 종료 회차의 행은 읽지 않는다.
     */
    @AcademyScopeExempt(reason = "Redis 최신 좌표가 비었을 때의 대체 조회 — 호출자가 이미 학원 범위로 확인한 회차 id 만 넘기고, run_position 에는 academy_id 컬럼이 부재하다")
    @Query(value = """
            SELECT p.*
            FROM run r
            JOIN LATERAL (
                SELECT * FROM run_position
                WHERE run_id = r.id AND recorded_at >= :since AND recorded_at < :until
                ORDER BY recorded_at DESC, id DESC
                LIMIT 1
            ) p ON true
            WHERE r.id IN (:runIds) AND r.status = 'moving'
            """, nativeQuery = true)
    List<RunPosition> findLatestMovingByRunIdIn(@Param("runIds") Collection<Long> runIds, @Param("since") OffsetDateTime since,
            @Param("until") OffsetDateTime until);

    /**
     * 위치 1건을 적재하되, 이 위치의 수신 시각 직전 1초({@code windowStart} 이상 {@code receivedAt} 이하)에 받은 위치가 이 회차에 이미 있으면(겹쳐 온 송신이
     * 먼저 커밋된 경우) 적재하지 않는다 — 한 회차의 송신을 줄 세우는 회차 행 잠금을 쥔 채 부른다. 잠금 뒤 새 문장이라 먼저 커밋된 행이 보인다. 수신 시각이 이 위치보다 늦은 행은
     * 막지 못한다 — 시계가 빠른 다른 인스턴스나 시계를 되돌린 서버가 남긴 행이 뒤따르는 위치를 계속 막지 않게 하는 {@code PositionIntakeGuard} 의 음수 간격 규칙과 같다.
     * {@code recordedFloor} 는 일 파티션 가지치기용 하한이다(기기 시각은 수신 시각과 허용 오차 안으로만 저장된다).
     *
     * @return 적재했으면 1, 겹친 송신이 먼저 있어 적재하지 않았으면 0
     */
    @AcademyScopeExempt(reason = "호출자가 이미 학원 범위로 잠그고 확인한 회차 id 만 넘긴다 — run_position 에는 academy_id 컬럼이 부재하다")
    @Modifying
    @Query(value = """
            INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at, speed, heading)
            SELECT :runId, :lat, :lng, :recordedAt, :receivedAt, CAST(:speed AS numeric), CAST(:heading AS numeric)
            WHERE NOT EXISTS (
                SELECT 1 FROM run_position
                WHERE run_id = :runId AND recorded_at >= :recordedFloor AND received_at >= :windowStart AND received_at <= :receivedAt)
            """, nativeQuery = true)
    int insertUnlessOverlapping(@Param("runId") Long runId, @Param("lat") BigDecimal lat, @Param("lng") BigDecimal lng,
            @Param("recordedAt") OffsetDateTime recordedAt, @Param("receivedAt") OffsetDateTime receivedAt,
            @Param("speed") BigDecimal speed, @Param("heading") BigDecimal heading,
            @Param("windowStart") OffsetDateTime windowStart, @Param("recordedFloor") OffsetDateTime recordedFloor);
}
