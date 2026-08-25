package src.backend.academy.dto;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;

/**
 * 학원 목록의 항목 하나(API_SPEC §6.1).
 *
 * @param staffCount 재직 관계자 수(정원 1명). 퇴사 행은 세지 않는다 — 목록에서 "관계자가 없는 학원"을
 *                   가려내는 것이 이 값의 쓰임이고, 퇴사자를 세면 그 판정이 뒤집힌다
 * @param userCount  소속 사용자 수 — 학부모·학생·기사·동승자 계정 합계
 */
public record AcademySummaryResponse(Long id, String code, String name, String region,
        long staffCount, long userCount, AcademyStatus status) {

    public static AcademySummaryResponse from(Academy academy, long staffCount, long userCount) {
        return new AcademySummaryResponse(academy.getId(), academy.getCode(), academy.getName(),
                academy.getRegion(), staffCount, userCount, academy.getStatus());
    }
}
