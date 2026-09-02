package src.backend.academy.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademySettingResponse;
import src.backend.academy.entity.AcademySetting;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 학원별 설정 조회(API_SPEC §5.21 GET, Phase 11 목표 4) — 학원 관계자 전용. {@code Permissions} 카탈로그에
 * 이 값이 없다(§6.2 표 자체가 "학원 설정(미승차 대기)" 행을 권한 상수로 매핑하지 않은 3종 중 하나) —
 * 그래서 {@code @PreAuthorize} 대신 {@link #requireStaff} 로 이 클래스 안에서 직접 판정한다
 * ({@code BoardingCommandService.requireEscort} 와 같은 근거).
 *
 * <p>{@code academy_setting} 행이 아직 없는 학원(등록 경로가 만들기 전의 구 데이터)은 조회 시점에
 * 기본값으로 만들어 자가 치유한다 — {@link src.backend.academy.command.AcademyCommandService#register}
 * 가 이제는 함께 만들지만, 그 이전에 등록된 학원까지 소급하지는 않으므로 이 자리가 여전히 필요하다.
 */
@Service
@RequiredArgsConstructor
public class AcademySettingQueryService {

    private final AcademySettingRepository academySettingRepository;

    @Transactional
    public AcademySettingResponse get(AuthUser requester) {
        requireStaff(requester);
        AcademySetting setting = academySettingRepository.findById(requester.academyId())
                .orElseGet(() -> academySettingRepository.save(AcademySetting.forAcademy(requester.academyId())));
        return AcademySettingResponse.from(setting);
    }

    private void requireStaff(AuthUser requester) {
        if (requester.role() != Role.STAFF) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
