package src.backend.student.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.student.entity.Stop;

/** {@link Stop} 영속성 접근. */
public interface StopRepository extends JpaRepository<Stop, Long> {

    /**
     * 좌표 부근의 승하차지 후보(STU-05 근접 병합) — {@code ix_stop_academy_coord} 를 그대로 탄다.
     *
     * <p><b>학원 조건이 쿼리에 고정</b>돼 있다(ERD {@code stop.academy_id} — "매칭 범위는 학원 안").
     * 호출부에 맡기면 조건 하나가 빠져도 동작하고, 그때 다른 학원의 승하차지에 학생이 붙는다 —
     * 그 상태에서도 응답은 정상이라 화면에 드러나지 않는다.
     *
     * <p>정사각형 상자로 <b>후보만</b> 좁힌다. 실제 임계 판정은 호출부가 거리로 하고, 그래서 이
     * 상자는 임계 원의 <b>초과집합</b>이어야 한다({@code StopProximity#searchBoxDegrees}) — 상자가
     * 원보다 좁으면 임계 안의 승하차지를 놓쳐 같은 자리에 승하차지가 둘 생긴다.
     */
    @Query("""
            SELECT st FROM Stop st
            WHERE st.academyId = :academyId
              AND st.lat BETWEEN :lat - :box AND :lat + :box
              AND st.lng BETWEEN :lng - :box AND :lng + :box
            """)
    List<Stop> findNearbyInAcademy(@Param("academyId") Long academyId, @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng, @Param("box") BigDecimal box);

    /**
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
}
