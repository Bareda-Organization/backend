package src.backend.student.repository.spec;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Student;

/**
 * 학생 저장소.
 * - findByUserId: 학생 로그인 계정 → 본인 Student 해석(본인 기록 조회)
 * - findByAssignedBusId: 버스별 탑승 명단·정원 계산
 * - findByAssignedBusIdIn: 버스 목록 화면의 N+1 제거(버스 수만큼 나던 쿼리를 1회로)
 */
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByUserId(Long userId);

    List<Student> findByTenantId(Long tenantId);

    List<Student> findByAssignedBusId(Long busId);

    List<Student> findByAssignedBusIdIn(Collection<Long> busIds);
}
