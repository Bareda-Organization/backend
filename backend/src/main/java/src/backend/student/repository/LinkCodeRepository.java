package src.backend.student.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.student.entity.LinkCode;

/** {@link LinkCode} 영속성 접근. */
public interface LinkCodeRepository extends JpaRepository<LinkCode, Long> {

    /**
     * 학부모가 입력한 코드 문자열에 대응하는 발급분 — <b>그 보호자에게 발급된 것만</b>이다(§3.4).
     *
     * <p>보호자 조건이 이 쿼리의 핵심이다. 코드 문자열만으로 찾으면 6자리 숫자를 훑는 다른 학부모가
     * 남의 자녀를 가져간다 — 코드 대조를 서버가 한다는 전제(§3.4)는 "누구의 코드인가" 까지 서버가
     * 본다는 뜻이다.
     *
     * <p><b>유효성 조건({@code used_at}·{@code expires_at})은 여기 넣지 않는다.</b> 그 판정은
     * {@link LinkCode#isUsable} 한 곳에 두어, 만료·재사용·불일치가 같은 {@code 403} 으로 합쳐지는
     * 자리를 쿼리와 엔티티 둘로 나누지 않는다.
     *
     * <p>학원 조건은 {@code guardian} 부모를 조인해 건다(ERD §6.1 부모 경유). 6자리 숫자는 학원을
     * 넘어 충돌할 수 있어, 조건이 없으면 남의 학원 발급분이 후보에 섞인다.
     *
     * <p>같은 보호자에게 살아 있는 코드가 여럿일 수 있어(요청을 여러 번 보낸 경우) 목록으로 돌려주고
     * <b>정렬을 고정</b>한다 — 호출부가 그중 쓸 수 있는 것을 고른다.
     */
    @Query("""
            SELECT lc FROM LinkCode lc
            JOIN LinkRequest lr ON lr.id = lc.linkRequestId
            JOIN Guardian g ON g.id = lr.guardianId
            WHERE lc.code = :code
              AND lr.guardianId = :guardianId
              AND g.academyId = :academyId
            ORDER BY lc.createdAt DESC, lc.id DESC
            """)
    List<LinkCode> findByCodeForGuardian(@Param("code") String code, @Param("guardianId") Long guardianId,
            @Param("academyId") Long academyId);
}
