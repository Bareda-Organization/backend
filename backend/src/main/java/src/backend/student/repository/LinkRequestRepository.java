package src.backend.student.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.student.entity.LinkRequest;
import src.backend.student.entity.LinkRequestStatus;

/** {@link LinkRequest} 영속성 접근. */
public interface LinkRequestRepository extends JpaRepository<LinkRequest, Long> {

    /**
     * 학생이 코드를 만들 근거가 되는 <b>대기 중이고 아직 만료되지 않은</b> 연결 요청(S-05, §3.3).
     *
     * <p>학원 조건은 {@code student} 부모를 조인해 건다 — {@code link_request} 에는
     * {@code academy_id} 컬럼이 부재해 조건을 붙일 자리가 부모 조인뿐이다(ERD §6.1 부모 경유).
     *
     * <p>만료를 {@code status} 가 아니라 {@code expires_at} 으로 판정한다. 만료 상태로 바꿔 주는 것은
     * 정리 배치인데 그 배치가 늦으면 {@code status='pending'} 인 만료 요청이 남고, 상태만 보는 구현은
     * 그것으로 코드를 발급한다.
     *
     * <p>여러 보호자가 같은 학생에게 요청할 수 있어 <b>정렬을 고정</b>하고 {@link Limit} 으로 1건만
     * 집는다. 없으면 어느 요청에 코드가 붙을지 DB 가 정하고, 그 순서는 계약이 아니다.
     */
    @Query("""
            SELECT lr FROM LinkRequest lr
            JOIN Student s ON s.id = lr.studentId
            WHERE lr.studentId = :studentId
              AND s.academyId = :academyId
              AND lr.status = :status
              AND lr.expiresAt >= :now
            ORDER BY lr.requestedAt DESC, lr.id DESC
            """)
    List<LinkRequest> findPendingForStudent(@Param("studentId") Long studentId,
            @Param("academyId") Long academyId, @Param("status") LinkRequestStatus status,
            @Param("now") OffsetDateTime now, Limit limit);
}
