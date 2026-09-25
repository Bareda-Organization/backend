package src.backend.notification.query;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationSettingResponse;
import src.backend.notification.entity.NotificationSetting;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * 알림 설정 조회(API_SPEC §3.14 GET, Phase 12 목표 7) — 학부모·학생 전용. 인가 판정은 서비스가
 * 아니라 컨트롤러의 {@code @PreAuthorize} 메타 애너테이션이 한다({@code Permissions.NOTIFICATION_SETTING_WRITE}
 * 가 이미 학부모·학생에게만 부여돼 있어 — {@code CanWriteIntent} 와 같은 근거로 새 권한 상수를
 * 만들지 않았다).
 *
 * <p>{@code notification_setting} 행이 아직 없는 계정은 기본값(전부 on) DTO 를 그 자리에서
 * 만들어 응답한다 — <b>저장하지 않는다</b>(BR-096, Ruling — §7 CQRS "조회는 상태를 바꾸지 않는다").
 * 행을 만드는 자가 치유는 {@link src.backend.notification.command.NotificationSettingCommandService}
 * PATCH 쪽에만 있다. 예전에는 이 조회도 get-or-create 로 행을 만들었는데, 같은 계정이 행 없이
 * 두 기기에서 동시에 조회하면 두 트랜잭션 모두 빈 결과를 보고 각자 저장을 시도해 뒤 커밋이
 * PK UNIQUE 위반(500)이 됐다. 행 부재를 "off" 로 읽지 않는 이유는 {@code NotificationDispatcher}
 * 의 발송 판정에서도 같다 — 행이 없으면 켜진 것으로 본다(DDL {@code DEFAULT true} 와 일치).
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingQueryService {

    private final NotificationSettingRepository notificationSettingRepository;

    private final Clock clock;

    /** 알림 설정 조회(§3.14) — 행이 없으면 저장 없이 기본값 DTO 만 돌려준다. */
    @Transactional(readOnly = true)
    public NotificationSettingResponse get(AuthUser requester) {
        NotificationSetting setting = notificationSettingRepository.findById(requester.accountId())
                .orElseGet(() -> NotificationSetting.forAccount(requester.accountId(), OffsetDateTime.now(clock)));
        return NotificationSettingResponse.from(setting);
    }
}
