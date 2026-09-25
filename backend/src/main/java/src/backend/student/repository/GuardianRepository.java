package src.backend.student.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Guardian;

/** {@link Guardian} 영속성 접근. */
public interface GuardianRepository extends JpaRepository<Guardian, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 보호자 쪽에 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<Guardian> findByAccountId(Long accountId);

    /**
     * 자녀 연결 코드 입력 1회를 창 안의 시도로 센다(§3.4 · BR-024) — 상한에 이미 닿았으면 갱신 행 0.
     *
     * <p>판정과 증가를 <b>한 조건부 UPDATE</b> 로 한다. 읽고 나서 올리면 동시에 보낸 요청이 모두 같은
     * 횟수를 읽고 지나가 상한이 동시 요청 수만큼 늘어난다. 창이 {@code windowFloor} 이전에 열렸으면
     * 새 창을 연다. 엔티티에 매핑하지 않은 칸이라 네이티브로 쓴다.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE guardian
            SET link_attempt_count = CASE WHEN link_attempt_window_start IS NULL
                        OR link_attempt_window_start <= :windowFloor THEN 1 ELSE link_attempt_count + 1 END,
                link_attempt_window_start = CASE WHEN link_attempt_window_start IS NULL
                        OR link_attempt_window_start <= :windowFloor THEN :now ELSE link_attempt_window_start END
            WHERE id = :id AND academy_id = :academyId
              AND (link_attempt_window_start IS NULL OR link_attempt_window_start <= :windowFloor
                   OR link_attempt_count < :maxAttempts)
            """)
    int consumeLinkAttempt(@Param("id") Long id, @Param("academyId") Long academyId,
            @Param("now") OffsetDateTime now, @Param("windowFloor") OffsetDateTime windowFloor,
            @Param("maxAttempts") int maxAttempts);
}
