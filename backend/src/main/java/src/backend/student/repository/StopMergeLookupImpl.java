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
 * <p>{@code spec}/{@code impl} 로 가르지 않은 것은 프레임워크 제약 때문이 아니라 {@code CLAUDE.md} 의
 * 분리 기준("구현이 바뀔 가능성이 있는가")에 걸리지 않기 때문이다 — 구현 후보가 PostgreSQL 자문 잠금
 * 하나뿐이고, 인터페이스를 둔 목적도 교체가 아니라 <b>잠금 없는 후보 조회를 없애는 것</b>이다.
 *
 * <p>그래도 나중에 가르려거든 <b>인터페이스 패키지의 하위</b>에 두어야 한다. Spring Data 는 조각
 * 구현을 인터페이스가 놓인 패키지와 그 하위에서만 찾는다 — 실측: {@code repository}+{@code repository.impl}
 * 과 {@code repository.spec}+{@code repository.spec.impl} 은 뜨고, 형제로 놓은
 * {@code repository.spec}+{@code repository.impl} 은 구현을 못 찾아 메서드 이름으로 쿼리를 만들려다
 * 컨텍스트가 죽는다({@code No property 'lockAcademy' found for type 'Stop'}).
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
     *
     * <p><b>{@code lock_timeout} 을 걸지 않는다.</b> 대기 시간의 상한은 잠금을 쥔 트랜잭션의 길이인데,
     * 그 트랜잭션에는 외부 호출이 부재하고(지오코딩은 {@code AddressVerification} 이 트랜잭션 밖에서
     * 끝낸다 — §7 규칙 16) 남는 것은 로컬 DB 작업뿐이라 상한이 이미 좁다. 반대로 시간 제한을 걸면
     * 대기가 <b>오류</b>로 바뀌는데, 그때 호출부가 할 수 있는 일이 재시도뿐이라 같은 잠금 경합을 다시
     * 만든다. 값의 성격도 정책 상수(§7 규칙 10)가 아니라 인프라 가드라 코드 상수로 박을 자리가 아니다.
     * 저빈도 연산이라는 전제가 깨지면(대량 일괄 등록) Ruling 179 의 대가와 함께 재판정할 항목이다.
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
