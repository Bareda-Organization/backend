package src.backend.student.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.student.entity.Stop;

/**
 * {@link StopMergeLookup} 구현 — 두 문장을 한 메서드에 묶어 잠금이 조회보다 앞서는 것을 보장한다.
 *
 * <p>Spring Data 조각(fragment) 규약이 이름을 {@code StopMergeLookup} + {@code Impl} 로, 위치를
 * 저장소 기본 패키지 아래로 고정하므로 {@code spec}/{@code impl} 로 가르지 않는다.
 */
class StopMergeLookupImpl implements StopMergeLookup {

    /**
     * 잠금 범위는 <b>학원 하나</b>다 — 좌표·격자로 좁히면 임계 원 안인데 다른 칸에 놓인 두 점이 서로
     * 다른 잠금을 잡아 그대로 지나간다(Ruling 179 가 격자 키 UNIQUE 를 기각한 것과 같은 구멍).
     *
     * <p>{@code _xact_} 인 것은 트랜잭션이 끝날 때 자동으로 풀리기 때문이다. 세션 잠금을 쓰면 반납을
     * 빠뜨린 경로 하나가 커넥션 풀에 잠금을 남긴 채로 돌아간다.
     *
     * <p>대가는 매칭에 성공해 생성하지 않는 경로까지 학원 단위로 직렬화되는 것이고, 받아들이는 근거는
     * 승하차지 생성이 학생 등록·주소 수정 시점에만 도는 저빈도 연산이라는 것이다.
     */
    private static final String ACADEMY_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtext('stop:' || cast(:academyId as text)))";

    /**
     * 정사각형 상자로 <b>후보만</b> 좁힌다 — 실제 임계 판정은 호출부가 거리로 하고, 그래서 이 상자는
     * 임계 원의 <b>초과집합</b>이어야 한다({@code StopProximity#searchBoxDegrees}). 상자가 원보다
     * 좁으면 임계 안의 승하차지를 놓쳐 같은 자리에 승하차지가 둘 생긴다.
     *
     * <p>학원 조건이 쿼리에 고정돼 있다(§7 규칙 7) — 호출부에 맡기면 조건 하나가 빠져도 동작하고,
     * 그때 다른 학원의 승하차지에 학생이 붙는다.
     */
    private static final String NEARBY_JPQL = """
            SELECT st FROM Stop st
            WHERE st.academyId = :academyId
              AND st.lat BETWEEN :lat - :box AND :lat + :box
              AND st.lng BETWEEN :lng - :box AND :lng + :box
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Stop> lockAcademyAndFindNearby(Long academyId, BigDecimal lat, BigDecimal lng, BigDecimal box) {
        acquireAcademyLock(academyId);
        return entityManager.createQuery(NEARBY_JPQL, Stop.class)
                .setParameter("academyId", academyId)
                .setParameter("lat", lat)
                .setParameter("lng", lng)
                .setParameter("box", box)
                .getResultList();
    }

    /**
     * 트랜잭션 밖이면 잠그지 않고 거부한다 — 그 자리에서 막지 않으면 아무것도 막지 못한 채 초록이 된다.
     *
     * <p>자문 잠금은 트랜잭션이 끝날 때 풀리므로, 트랜잭션이 없으면 문장이 끝나는 즉시 풀려 임계
     * 구역이 성립하지 않는다. 그런데도 응답은 정상이라 중복 생성은 동시 요청에서만 드러난다.
     */
    private void acquireAcademyLock(Long academyId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("승하차지 병합은 트랜잭션 안에서만 부를 수 있다 — 자문 잠금이 곧바로 풀린다");
        }
        entityManager.createNativeQuery(ACADEMY_LOCK_SQL)
                .setParameter("academyId", academyId)
                .getSingleResult();
    }
}
