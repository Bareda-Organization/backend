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
 * <p><b>보낸 항목만 바꾼다(Ruling 869)</b> — API_SPEC §1.14 의 공통 규칙대로 빠진 키와 {@code null} 은 유지이고, 아무 키도
 * 없는 요청은 바꿀 것이 없어 값 그대로 200 이다. 이전에는 세 항목 전부를 요구해 하나만 보내면 422 였다. 대상 밖 키만 422 로 막는다.
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

    /** 대상 밖 키(예: 지연 알림 {@code delay})가 섞이면 422 — 세 항목은 일부만 보내도 된다. */
    private void validate(Map<String, Boolean> request) {
        if (request == null || !ALLOWED_KEYS.containsAll(request.keySet())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "arrive, boarding, no_show 항목만 boolean 값으로 전달할 수 있습니다");
        }
    }
}
