package src.backend.student.repository.spec;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Student;

/**
 * 학생 저장소.
 * - findByUserId: 학생 로그인 계정 → 본인 Student 해석(본인 기록 조회). 비활성이어도 자기 기록은 봐야 하므로 걸러내지 않는다
 * - findByTenantIdAndActiveTrue: 학원 명단(재원생만)
 * - findByAssignedBusIdAndActiveTrue: 버스별 탑승 명단·정원 계산
 * - findByAssignedBusIdInAndActiveTrue: 버스 목록 화면의 N+1 제거(버스 수만큼 나던 쿼리를 1회로)
 * - findByTenantId: 관리자 목록의 "비활성 포함" 옵션 전용
 *
 * ⚠️ 필터를 {@code @Where}·{@code @Filter} 같은 전역 장치로 넣지 않고 **메서드 이름**에 박는다(I-9).
 * 전역 필터는 조용히 걸러져 "왜 안 나오지"를 추적할 수 없고, 비활성까지 봐야 하는 화면이 빠져나갈 구멍도 없다.
 * 이름에 박아 두면 호출부가 무엇을 조회하는지 드러나고, 이름이 바뀔 때 누락이 컴파일 에러로 드러난다.
 */
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByUserId(Long userId);

    List<Student> findByTenantIdAndActiveTrue(Long tenantId);

    List<Student> findByAssignedBusIdAndActiveTrue(Long busId);

    List<Student> findByAssignedBusIdInAndActiveTrue(Collection<Long> busIds);

    /** 비활성(퇴원) 학생까지 포함한 학원 전체 — 관리자 목록의 {@code includeInactive=true} 전용이다. */
    List<Student> findByTenantId(Long tenantId);
}
