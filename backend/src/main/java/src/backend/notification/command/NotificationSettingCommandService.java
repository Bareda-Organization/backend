package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationSettingResponse;
import src.backend.notification.entity.NotificationSetting;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * 알림 설정 수정(API_SPEC §3.14 PATCH, Phase 12 목표 7·9) — 학부모·학생 전용. get-or-create 는
 * {@link src.backend.notification.query.NotificationSettingQueryService} 와 같은 근거다.
 *
 * <p>요청 바디를 고정 3필드 레코드가 아니라 {@code Map<String, Boolean>} 으로 받는다 — 목표 9
 * ("설정 대상 밖 항목 전달 시 422")가 요구하는 것은 <b>알 수 없는 키가 왔다는 사실 자체를 서비스가
 * 볼 수 있어야</b> 하는데, 고정 3필드 레코드에 자동 바인딩하면 Jackson 이 미지정 키를 조용히
 * 버려 그 사실이 아예 도달하지 않는다.
 *
 * <p><b>버린 대안</b> — {@code @JsonIgnoreProperties(ignoreUnknown = false)} + 새 전역
 * {@code HttpMessageNotReadableException} 핸들러. {@code GlobalExceptionHandler} 에 그 예외
 * 핸들러가 아직 없어, 추가하면 이 앱의 <b>모든</b> JSON 바디 파싱에 영향을 준다 — 이 태스크의
 * 범위(알림 설정 하나)를 넘는 횡단 변경이라 채택하지 않았다. {@code Map} 수신 + 서비스 계층 명시
 * 검증은 {@code RiderStatusUpdateRequest} 가 이미 쓰는 관례(자동 바인딩보다 서비스 계층 판정을
 * 우선)와도 같은 방향이다.
 *
 * <p>3개 키를 전부 요구하는(부분 갱신 불허) 이유는 API_SPEC §3.14 필드 표가 GET·PATCH 를 같은 표로
 * 묶고 3개 전부를 "필수" 로 적었기 때문이다 — {@code AcademySettingUpdateRequest} 의 단일 필드
 * 전체 교체와 같은 해석이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingCommandService {

    private static final Set<String> ALLOWED_KEYS = Set.of("arrive", "boarding", "no_show");

    private final NotificationSettingRepository notificationSettingRepository;

    private final Clock clock;

    @Transactional
    public NotificationSettingResponse update(AuthUser requester, Map<String, Boolean> request) {
        validate(request);
        NotificationSetting setting = notificationSettingRepository.findById(requester.accountId())
                .orElseGet(() -> notificationSettingRepository.save(
                        NotificationSetting.forAccount(requester.accountId(), OffsetDateTime.now(clock))));
        setting.changeSettings(request.get("arrive"), request.get("boarding"), request.get("no_show"),
                OffsetDateTime.now(clock));
        return NotificationSettingResponse.from(setting);
    }

    /**
     * 키 집합이 {@code arrive}·{@code boarding}·{@code no_show} 정확히 3개와 같은지 본다 —
     * 대상 밖 키가 섞여 있거나(목표 9) 3개 중 하나라도 빠지면 같은 422 다. 값 자체가 {@code null}
     * (JSON {@code "arrive": null})인 경우도 같이 걸러 {@code changeSettings} 의 오토언박싱
     * {@code NullPointerException} 이 500 으로 새는 것을 막는다.
     */
    private void validate(Map<String, Boolean> request) {
        if (request == null || !ALLOWED_KEYS.equals(request.keySet()) || request.containsValue(null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "arrive, boarding, no_show 세 항목만 boolean 값으로 전달해야 합니다");
        }
    }
}
