package src.backend.academy.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademySettingResponse;
import src.backend.academy.dto.AcademySettingUpdateRequest;
import src.backend.academy.entity.AcademySetting;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 학원별 설정 수정(API_SPEC §5.21 PATCH, Phase 11 목표 4) — 학원 관계자 전용. 권한 판정 근거는
 * {@link src.backend.academy.query.AcademySettingQueryService} 와 같다({@code Permissions} 카탈로그
 * 미매핑 3종 중 하나라 {@code @PreAuthorize} 대신 {@link #requireStaff} 로 직접 판정).
 *
 * <p>행이 없는 학원도 갱신 대상일 수 있어(자가 치유 전 구 데이터) GET 과 같은 GET-or-create 를 먼저
 * 하고 그 위에 값을 바꾼다 — 없으면 404 로 막으면 그 학원은 영원히 기본값을 못 벗어난다.
 */
@Service
@RequiredArgsConstructor
public class AcademySettingCommandService {

    private final AcademySettingRepository academySettingRepository;

    @Transactional
    public AcademySettingResponse update(AuthUser requester, AcademySettingUpdateRequest request) {
        requireStaff(requester);
        AcademySetting setting = academySettingRepository.findById(requester.academyId())
                .orElseGet(() -> academySettingRepository.save(AcademySetting.forAcademy(requester.academyId())));
        setting.changeNoShowWaitMinutes(request.noShowWaitMinutes());
        return AcademySettingResponse.from(setting);
    }

    private void requireStaff(AuthUser requester) {
        if (requester.role() != Role.STAFF) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
