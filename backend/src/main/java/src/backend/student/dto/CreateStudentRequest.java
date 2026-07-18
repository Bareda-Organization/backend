package src.backend.student.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 학생 등록 요청 — 등록 + 배정(버스·정류장) + 보호자 연결을 한 번에.
 *
 * <p>tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 * userId(학생 로그인 계정)·assignedBusId·boardingStopId·guardians 는 모두 생략 가능.
 */
public record CreateStudentRequest(
        Long tenantId,
        @NotBlank String name,
        Long userId,
        Long assignedBusId,
        Long boardingStopId,
        List<GuardianLink> guardians) {

    /** 보호자 연결 항목 — 보호자(학부모) User id + 관계(예: "모", "부"). */
    public record GuardianLink(
            @NotNull Long guardianUserId,
            String relation) {
    }
}
