package src.backend.student.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 학생 등록 요청 — 등록 + 배정(버스·정류장) + 보호자 연결을 한 번에.
 *
 * <p>tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 * userId(학생 로그인 계정)·assignedBusId·boardingStopId·guardians 는 모두 생략 가능.
 */
public record CreateStudentRequest(
        @Schema(example = "1", description = "소속 학원 id(한빛학원)") Long tenantId,
        @NotBlank @Schema(example = "이하은") String name,
        @Schema(description = "학생 로그인 계정(app_user) id — 생략 시 계정 없이 등록") Long userId,
        @Schema(example = "1", description = "담당 버스 id(3호차)") Long assignedBusId,
        @Schema(example = "1", description = "기본 승차 정류장 id(정류장 A)") Long boardingStopId,
        List<GuardianLink> guardians) {

    /** 보호자 연결 항목 — 보호자(학부모) User id + 관계(예: "모", "부"). */
    public record GuardianLink(
            @NotNull @Schema(example = "2", description = "보호자 user id(이부모)") Long guardianUserId,
            @Schema(example = "모") String relation) {
    }
}
