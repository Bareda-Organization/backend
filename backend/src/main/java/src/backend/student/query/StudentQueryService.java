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

    /**
     * 학원 학생 목록. 기본은 <b>활성(재원) 학생만</b>이다(I-9) —
     * 기본값을 "전부"로 두면 퇴원생이 배차·명단·시뮬레이션에 계속 끼어든다.
     */
    @Transactional(readOnly = true)
    public List<StudentResponse> list(AuthUser admin, Long tenantId, boolean includeInactive) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<Student> students = includeInactive
                ? studentRepository.findByTenantId(effectiveTenant)
                : studentRepository.findByTenantIdAndActiveTrue(effectiveTenant);
        return students.stream().map(StudentResponse::of).toList();
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
