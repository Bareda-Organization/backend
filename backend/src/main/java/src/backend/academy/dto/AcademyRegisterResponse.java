package src.backend.academy.dto;

import java.util.List;

import src.backend.academy.entity.Academy;

/**
 * 학원 등록 응답(API_SPEC §6.2) — 생성된 학원 코드를 여기서 돌려받는 것이 유일한 전달 경로다.
 *
 * @param warnings 저장을 막지 않은 경고 목록. 비어 있어도 필드를 빼지 않는다 — 클라이언트가 존재
 *                 여부로 분기하면 경고가 없는 정상 응답마다 다른 형태를 다뤄야 한다
 */
public record AcademyRegisterResponse(Long academyId, String code, String name, String region,
        List<AcademyWarning> warnings) {

    public static AcademyRegisterResponse from(Academy academy, List<AcademyWarning> warnings) {
        return new AcademyRegisterResponse(academy.getId(), academy.getCode(), academy.getName(),
                academy.getRegion(), warnings);
    }
}
