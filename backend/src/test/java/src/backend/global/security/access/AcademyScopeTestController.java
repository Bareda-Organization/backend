package src.backend.global.security.access;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 학원 격리 검증 전용 컨트롤러 — 테스트 소스에만 존재한다({@code GateTestController} 와 같은 이유).
 * Phase 2 에는 학원 범위 자원을 조회하는 프로덕션 엔드포인트가 아직 없어(학생·회차는 Phase 5·9),
 * 격리가 HTTP 왕복에서 {@code 403 ACADEMY_SCOPE_VIOLATION} 으로 드러나는지 볼 다른 수단이 부재하다.
 *
 * <p><b>여기에 격리 판정을 두지 않는다.</b> 판정은 전부 프로덕션 코드({@link AcademyScope} ·
 * {@code StudentRepository} 의 학원 조건 · {@code GlobalExceptionHandler} 의 상태 변환)가 하고, 이
 * 클래스는 그 셋을 잇기만 한다 — 판정을 여기에 복제하면 테스트가 자기 자신을 검사하게 된다.
 *
 * <p>목록 응답이 학생 id 만 담는 이유는 격리 여부가 <b>어느 학원 행이 섞였는가</b> 로만 판정되기
 * 때문이다. 자원 표현(DTO)은 그 소유 Phase 가 정한다.
 *
 * <p><b>이 형태를 Phase 3 {@code /admin/**} 이 복사할 때</b> — 학원 경로와 메인 관리자 경로는 학원
 * 조건만 다르고 {@code deleted_at IS NULL} 은 <b>양쪽 다 필요</b>하다. 한쪽만 걸면 같은 화면이 학원을
 * 고르느냐에 따라 퇴원생이 나왔다 사라진다. 여기서도 두 경로가 각각 그 조건을 가진 저장소 메서드를
 * 부른다 — {@code findAll()} 로 대신하면 조건이 빠진다.
 */
@RestController
class AcademyScopeTestController {

    private final StudentRepository studentRepository;

    AcademyScopeTestController(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @AuthenticatedOnly
    @GetMapping("/academy-scope-test/students")
    public List<Long> students(@AuthenticationPrincipal AuthUser authUser,
            @RequestParam(name = "academy_id", required = false) Long requestedAcademyId) {
        return AcademyScope.resolveListScope(authUser, requestedAcademyId)
                .map(studentRepository::findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc)
                .orElseGet(studentRepository::findAllByDeletedAtIsNullOrderByNameAsc)
                .stream()
                .map(Student::getId)
                .toList();
    }

    @AuthenticatedOnly
    @GetMapping("/academy-scope-test/students/{studentId}")
    public Long student(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long studentId) {
        Student student = studentRepository.findById(studentId).orElseThrow();
        AcademyScope.assertAccessible(authUser, student.getAcademyId());
        return student.getId();
    }
}
