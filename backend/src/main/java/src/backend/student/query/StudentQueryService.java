package src.backend.student.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentResponse;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/** 학생 목록/상세 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class StudentQueryService {

    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public StudentQueryService(StudentRepository studentRepository,
                               StudentGuardianRepository studentGuardianRepository) {
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<StudentResponse> list(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return studentRepository.findByTenantId(effectiveTenant).stream()
                .map(StudentResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse get(AuthUser admin, Long studentId) {
        Student student = loadAccessibleStudent(admin, studentId);
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    private Student loadAccessibleStudent(AuthUser admin, Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, student.getTenant().getId()); // 접근 권한 검증
        return student;
    }
}
