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
 * <p>{@code notification_setting} 행이 아직 없는 계정(2026-09-02 기준 운영에 이 테이블을 쓰는
 * 호출부가 부재 — {@code forAccount} 의 실제 호출자가 아직 없다)은 조회 시점에 기본값(전부 on)으로
 * 만들어 자가 치유한다 — {@link src.backend.academy.query.AcademySettingQueryService} 와 같은
 * get-or-create 패턴이다. 행 부재를 "off" 로 읽지 않는 이유는 {@code NotificationDispatcher} 의
 * 발송 판정에서도 같다 — 행이 없으면 켜진 것으로 본다(DDL {@code DEFAULT true} 와 일치).
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingQueryService {

    private final NotificationSettingRepository notificationSettingRepository;

    private final Clock clock;

    @Transactional
    public NotificationSettingResponse get(AuthUser requester) {
        NotificationSetting setting = notificationSettingRepository.findById(requester.accountId())
                .orElseGet(() -> notificationSettingRepository.save(
                        NotificationSetting.forAccount(requester.accountId(), OffsetDateTime.now(clock))));
        return NotificationSettingResponse.from(setting);
    }
}
