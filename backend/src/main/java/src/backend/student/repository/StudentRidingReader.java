package src.backend.student.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 한 학생이 확정된 회차에서 어느 승하차지에 타는지 읽는다 — 퇴원 미리보기(STU-04)가 쓴다. 회차 명단 전체를 읽어 한 학생을 Java 에서 골라내면
 * 학생이 많은 학원일수록 요청 하나가 읽는 행이 늘어, 그 학생의 행만 묻는다. 명단 테이블은 다른 모듈 소유라 그 모듈의 저장소를 들이지 않고
 * 읽기 전용 네이티브 질의로 두 컬럼만 가져온다.
 */
@Repository
@RequiredArgsConstructor
public class StudentRidingReader {

    private final EntityManager entityManager;

    /**
     * 회차 id → 그 학생의 승하차지 id. 결석 처리된 회차와 이 학원의 회차가 아닌 것은 빠진다 — 학원 조건은 회차 쪽에 건다.
     */
    @SuppressWarnings("unchecked")
    public Map<Long, Long> stopIdsByRunId(Long academyId, Long studentId, Collection<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT rr.run_id, rr.stop_id FROM run_rider rr
                  JOIN run r ON r.id = rr.run_id
                 WHERE rr.student_id = :studentId AND rr.run_id IN (:runIds)
                   AND r.academy_id = :academyId AND rr.status <> 'absent'
                """)
                .setParameter("studentId", studentId)
                .setParameter("runIds", runIds)
                .setParameter("academyId", academyId)
                .getResultList();
        Map<Long, Long> stopIds = new HashMap<>();
        for (Object[] row : rows) {
            stopIds.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return stopIds;
    }
}
