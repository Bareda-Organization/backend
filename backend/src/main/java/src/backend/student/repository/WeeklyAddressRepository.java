package src.backend.student.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
