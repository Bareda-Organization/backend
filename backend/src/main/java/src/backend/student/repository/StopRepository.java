package src.backend.student.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.student.entity.Stop;

/**
 * {@link Stop} 영속성 접근.
 *
 * <p>근접 병합 후보 조회는 {@link StopMergeLookup} 이 갖는다 — 잠금 없는 후보 조회를 여기 두면
 * 호출부가 잠금을 빠뜨려도 컴파일되고, 그때 같은 자리에 승하차지가 둘 생긴다(Ruling 179).
 *
 * <p><b>{@link #findNearby} 는 그 규칙의 한 자리 예외이며, 쓰기 경로에서 부르면 안 된다</b>
 * (2026-09-22 주소 검색). 검색은 <b>아무것도 만들지 않으므로</b> Ruling 179 가 막으려는 "조회 →
 * 판정 → 생성" 임계 구역 자체가 없고, 그 경로에 학원 잠금을 걸면 화면 조회가 그 학원의 주소
 * 등록·강제 추가를 기다리게 한다. 병합은 여전히 {@link StopMergeLookup} 하나뿐이고,
 * {@code StopMatcherUsesLockingLookupTest} 가 쓰기 경로의 호출 대상을 고정한다.
 */
public interface StopRepository extends JpaRepository<Stop, Long>, StopMergeLookup {

    /**
     * ⚠ <b>아래 {@code findAllByIdInAndAcademyId} 와 기능이 같다</b> — Phase 6 의 T5(고정 노선 편성)와
     * T4(계산 파이프라인)가 서로 다른 워크트리에서 각자 만들었고, 병합 시점에 통합하면 두 모듈의
     * 호출부를 고치는 리뷰 미경유 변경이 되어 그대로 뒀다. <b>하나로 합치는 것은 별도 단위다.</b>
     * 승하차지 여러 곳을 한 번에 읽는다 — 고정 노선 편성(RTE-01)이 요청받은 {@code stop_ids} 를
     * 검증할 때와 최적화(RTE-09)가 좌표를 채울 때 쓴다.
     *
     * <p>{@code findAllById} 를 쓰지 않는 이유는 그것이 <b>학원 조건을 붙일 자리가 부재한</b> 전건
     * 조회이기 때문이다(횡단 규칙 7 · {@code AcademyScopeRepositoryConventionTest}) — id 목록이 요청
     * 본문에서 오므로, 조건이 빠지면 남의 학원 승하차지가 그대로 편성에 들어간다.
     *
     * <p><b>돌려준 개수를 호출부가 요청 개수와 대조하는 것이 검증의 본체다</b> — 이 조회는 없는 것과
     * 남의 학원 것을 같은 "빠짐" 으로 돌려줄 뿐이고, 그 둘을 갈라 답하면 타 학원 승하차지의 존재
     * 여부가 응답에서 드러난다.
     */
    List<Stop> findAllByAcademyIdAndIdIn(Long academyId, Collection<Long> ids);

    /**
     * 노선 계산 ①단계가 쓸 승하차지 좌표를 한 번에 읽는다 — 학원 밖 승하차지는 <b>결과에서 빠진다.</b>
     *
     * <p>빠뜨리는 것이 이 조회의 일이다. 다른 학원의 승하차지 번호가 어떤 경로로 명단에 섞여
     * 들어오더라도 좌표를 얻지 못한 것으로 다뤄져 그 학생이 분리될 뿐, 남의 학원 주소가 이 학원
     * 버스의 정차지가 되지는 않는다(횡단 규칙 7).
     */
    List<Stop> findAllByIdInAndAcademyId(Collection<Long> ids, Long academyId);

    /**
     * 좌표 부근의 승하차지를 <b>잠그지 않고</b> 읽는다 — 화면에 "이 자리에 이미 있다" 를 알리는
     * 조회 전용 경로다(2026-09-22 주소 검색).
     *
     * <p>{@link StopMergeLookup#lockAcademyAndFindNearby} 와 가르는 이유는 <b>잠금이 쓰기의 것</b>이기
     * 때문이다. 검색은 아무것도 만들지 않으므로 임계 구역이 필요 없고, 학원 잠금을 걸면 그동안 그
     * 학원의 주소 등록·강제 추가가 이 조회를 기다린다.
     */
    @Query("""
            SELECT s FROM Stop s
             WHERE s.academyId = :academyId
               AND s.lat BETWEEN :lat - :box AND :lat + :box
               AND s.lng BETWEEN :lng - :box AND :lng + :box
            """)
    List<Stop> findNearby(@Param("academyId") Long academyId, @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng, @Param("box") BigDecimal box);
}
