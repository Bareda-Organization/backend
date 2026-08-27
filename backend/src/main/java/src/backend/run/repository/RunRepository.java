package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.common.enums.Direction;
import src.backend.run.entity.Run;

/** {@link Run} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface RunRepository extends JpaRepository<Run, Long> {

    /**
     * 한 학원의 <b>그 날짜</b> 회차 목록(SCH-02 결과 조회, §5.10 {@code GET /staff/runs}).
     *
     * <p>날짜 조건이 <b>쿼리에 고정</b>돼 있는 것이 요점이다 — 조건이 빠져도 목록은 그럴듯하게
     * 동작하고(ARCHITECTURE §6.1), 그 상태에서는 오늘 화면에 지난달 회차가 함께 뜬다.
     *
     * <p>취소된 회차({@code canceled_at} 이 채워진 것)도 싣는다 — 임시 취소는 <b>표시</b>이지 삭제가
     * 아니고(§5.10), 거르면 관계자가 무엇을 취소했는지 되읽을 경로가 사라진다.
     */
    List<Run> findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(Long academyId, LocalDate serviceDate);

    /**
     * 임시 취소·배치 대상 회차 1건(SCH-03 · MGR-05, §5.10·§5.14) — 학원이 어긋나면 빈 결과이고
     * 호출부가 그것을 {@code 404 RUN_NOT_FOUND} 로 답한다(Ruling 163: {@code {id}} 지목은 404).
     */
    Optional<Run> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 같은 유일성 조합의 회차가 이미 있는지 본다 — {@code uk_run_bus_date_direction_depart} 위반을
     * 저장 전에 막는다.
     *
     * <p>제약 자체에는 {@code academy_id} 가 없는데 학원 조건을 더한 것은 격리 규칙(횡단 규칙 7)을
     * 지키기 위함이다 — 차량이 한 학원에만 속하므로 결과가 달라지지 않는다.
     *
     * <p><b>이 선검사는 방어의 전부가 아니다.</b> 동시 실행 2건은 서로의 미커밋 INSERT 를 보지 못한 채
     * 둘 다 여기를 지나며, 그때 막는 것은 UNIQUE 제약이다.
     */
    boolean existsByAcademyIdAndBusIdAndServiceDateAndDirectionAndDepartTime(Long academyId, Long busId,
            LocalDate serviceDate, Direction direction, OffsetDateTime departTime);
}
