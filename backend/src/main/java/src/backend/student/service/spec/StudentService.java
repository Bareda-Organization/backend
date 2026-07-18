package src.backend.student.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.student.dto.CreateStudentRequest;
import src.backend.student.dto.LinkGuardianRequest;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentResponse;
import src.backend.student.dto.UpdateStudentAssignmentRequest;

/**
 * 학생 관리의 계약(인터페이스) — 등록(배정·보호자 포함)/목록/상세/재배정/보호자추가. 구현은 StudentServiceImpl.
 *
 * <p>모든 접근은 학원 격리(TenantGuard) 아래 이뤄진다.
 */
public interface StudentService {

    /** 학생 등록 — 이름+소속학원+(선택)버스·정류장·보호자 연결을 한 번에. */
    StudentDetailResponse create(AuthUser admin, CreateStudentRequest req);

    /** 학원 학생 목록. */
    List<StudentResponse> list(AuthUser admin, Long tenantId);

    /** 학생 상세 — 버스·정류장·보호자 포함. */
    StudentDetailResponse get(AuthUser admin, Long studentId);

    /** 재배정 — 담당 버스·기본 승차 정류장 갱신. */
    StudentResponse updateAssignment(AuthUser admin, Long studentId, UpdateStudentAssignmentRequest req);

    /** 보호자(학부모) 연결 추가. */
    StudentDetailResponse addGuardian(AuthUser admin, Long studentId, LinkGuardianRequest req);
}
