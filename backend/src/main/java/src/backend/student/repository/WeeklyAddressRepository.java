package src.backend.student.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.student.entity.WeeklyAddress;

/** {@link WeeklyAddress} 영속성 접근. */
public interface WeeklyAddressRepository extends JpaRepository<WeeklyAddress, Long> {

    /**
     * 한 학생의 요일별 주소 전부(P-05, API_SPEC §3.7) — 조회와 덮어쓰기가 <b>같은 조회</b>를 쓴다.
     *
     * <p>{@code weekly_address} 에는 {@code academy_id} 컬럼이 부재하므로 학원 조건을 붙일 자리가
     * <b>부모 조인뿐</b>이다(ERD §6.1 부모 경유). {@code studentId} 만으로 꺼내면 호출부가 학원을
     * 확인했는지에 격리가 매달리고, 확인을 빠뜨린 다음 경로가 생겨도 이 조회는 그대로 값을 돌려준다.
     *
     * <p>정렬을 걸지 않는다 — 요일이 {@code 'mon'}·{@code 'tue'} 같은 문자열이라 DB 정렬은 알파벳순이
     * 되어 월~일과 어긋난다. 순서는 응답 조립({@code WeeklyAddressResponse})이 enum 선언 순으로 세운다.
     */
    @Query("""
            SELECT wa FROM WeeklyAddress wa
            JOIN Student s ON s.id = wa.studentId
            WHERE wa.studentId = :studentId
              AND s.academyId = :academyId
            """)
    List<WeeklyAddress> findAllByStudentIdAndAcademyId(@Param("studentId") Long studentId,
            @Param("academyId") Long academyId);

    /**
     * 명단 학생들이 그 요일 · 그 방향에 서는 승하차지(C-16, 노선 계산 ①단계) — 학생 한 명당 최대 1행이다.
     *
     * <p>{@code verified} 와 {@code stopId IS NOT NULL} 은 <b>두 번째 방어선</b>이다. 좌표 미확보
     * 학생을 실제로 갈라내는 것은 호출부(노선 계산 ①단계)가 승하차지 번호를 못 얻은 학생을 분리하는
     * 쪽이고, 둘 중 어느 조건을 지워도 지금은 결과가 같다(음성 대조 N11·N12 로 실측 — 검증 전 행은
     * {@code stop_id} 도 비어 있어 서로를 가린다). 그래도 남겨 두는 이유는 <b>검증을 통과한 칸만
     * 계산에 들어간다</b> 는 규칙(ERD {@code weekly_address.verified})이 적힌 자리가 여기뿐이라,
     * 지우면 {@code stop_id} 를 검증 없이 채우는 경로가 생겼을 때 그 행이 조용히 정차지가 된다.
     *
     * <p>학원 조건을 {@code Student} 조인으로 거는 이유는 {@code weekly_address} 에
     * {@code academy_id} 컬럼이 부재하기 때문이다(ERD §6.1 부모 경유).
     */
    @Query("""
            SELECT wa.studentId AS studentId, wa.stopId AS stopId
            FROM WeeklyAddress wa
            JOIN Student s ON s.id = wa.studentId
            WHERE s.academyId = :academyId
              AND wa.studentId IN :studentIds
              AND wa.weekday = :weekday
              AND wa.direction = :direction
              AND wa.verified = true
              AND wa.stopId IS NOT NULL
            """)
    List<StudentDailyStop> findDailyStops(@Param("academyId") Long academyId,
            @Param("studentIds") Collection<Long> studentIds, @Param("weekday") Weekday weekday,
            @Param("direction") Direction direction);

    /**
     * {@link #findDailyStops} 의 역방향 — 학생 집합이 아니라 <b>편성된 노선의 정차지 집합</b>에서
     * 출발해 그 요일·방향에 실제로 그 정차지를 쓰는 학생을 되찾는다(확정 배치 RTE-08, Phase 7).
     *
     * <p>확정 배치는 회차의 학생 명단을 먼저 알지 못한다 — 아는 것은 편성된 노선의
     * {@code run_stop}(정차지 순서)뿐이고, 그 정차지에 실제로 서는 학생이 누구인지는 이 조회로
     * 거꾸로 구한다. 조건 구성은 {@link #findDailyStops} 와 동일하되 {@code IN} 절만 학생이 아니라
     * 정차지로 바꾼 것이라 검증(verified)·NULL 배제 근거도 그대로다.
     */
    @Query("""
            SELECT wa.studentId AS studentId, wa.stopId AS stopId
            FROM WeeklyAddress wa
            JOIN Student s ON s.id = wa.studentId
            WHERE s.academyId = :academyId
              AND wa.stopId IN :stopIds
              AND wa.weekday = :weekday
              AND wa.direction = :direction
              AND wa.verified = true
              AND wa.stopId IS NOT NULL
            """)
    List<StudentDailyStop> findDailyStopsByStopIds(@Param("academyId") Long academyId,
            @Param("stopIds") Collection<Long> stopIds, @Param("weekday") Weekday weekday,
            @Param("direction") Direction direction);
}
