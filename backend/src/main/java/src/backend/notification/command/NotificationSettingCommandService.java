package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationSettingResponse;
import src.backend.notification.dto.NotificationSettingUpdateRequest;
import src.backend.notification.entity.NotificationSetting;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * 알림 설정 수정(API_SPEC §3.14 PATCH) — 학부모·학생 전용. get-or-create 는
 * {@link src.backend.notification.query.NotificationSettingQueryService} 와 같은 근거다.
 *
 * <p>요청 형식(세 항목만 boolean, 대상 밖 키는 {@code 422})은 경계에서 {@link NotificationSettingUpdateRequest} 가 검증하므로 이 서비스는 값만 반영한다.
 * <b>보낸 항목만 바꾼다(Ruling 869)</b> — API_SPEC §1.14 의 공통 규칙대로 빠진 키와 {@code null} 은 유지이고, 아무 키도
 * 없는 요청은 바꿀 것이 없어 값 그대로 200 이다.
 *
 * <p>행이 없는 계정의 첫 변경은 {@code INSERT … ON CONFLICT DO NOTHING} 으로 만든다 — 같은 계정이 두 기기에서 동시에 첫 변경을 보내도 한 번만 만들어지고
 * 진 쪽이 기본 키 위반(500)을 받지 않는다. 이어서 읽는 행은 잠금으로 읽는다 — 같은 계정의 서로 다른 토글 변경 두 건이 겹쳐도 한쪽이 다른 쪽의 옛 값을 되써 지우지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingCommandService {

    private final NotificationSettingRepository notificationSettingRepository;

    private final Clock clock;

    @Transactional
    public NotificationSettingResponse update(AuthUser requester, NotificationSettingUpdateRequest request) {
        notificationSettingRepository.createIfAbsent(requester.accountId(), OffsetDateTime.now(clock));
        NotificationSetting setting = notificationSettingRepository.findLockedById(requester.accountId()).orElseThrow();
        setting.changeSettings(request.arrive(), request.boarding(), request.noShow(), OffsetDateTime.now(clock));
        return NotificationSettingResponse.from(setting);
    }
}
