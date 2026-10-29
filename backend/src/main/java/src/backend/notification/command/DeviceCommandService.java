package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.notification.dto.DeviceRegisterRequest;
import src.backend.notification.dto.DeviceRegisterResponse;
import src.backend.notification.entity.DevicePlatform;
import src.backend.notification.repository.DeviceTokenRepository;

/** 푸시 단말 등록·해지(NTF-12, API_SPEC §2.11). */
@Service
@RequiredArgsConstructor
public class DeviceCommandService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final Clock clock;

    /**
     * 단말을 등록한다 — 같은 {@code (accountId, deviceId)} 행이 이미 있으면 새 토큰으로 바꿔 심는다({@code DeviceToken} Javadoc "값 수정이 아니라
     * 행 대체"). 한 문장의 upsert 라 같은 단말을 겹쳐 등록해도 행이 늘지 않고 UNIQUE 위반도 없다. 같은 토큰을 가진 다른 계정의 유효 행은 먼저
     * 해지한다(BR-223 — 한 기기의 토큰은 한 계정에만 유효).
     */
    @Transactional
    public DeviceRegisterResponse register(Long accountId, DeviceRegisterRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        deviceTokenRepository.revokeOtherAccounts(request.token(), accountId, now);
        DevicePlatform platform = DevicePlatform.valueOf(request.platform().toUpperCase(Locale.ROOT));
        deviceTokenRepository.upsert(accountId, request.deviceId(), request.token(),
                platform.name().toLowerCase(Locale.ROOT), request.appVersion(), now);
        return new DeviceRegisterResponse(request.deviceId(), now);
    }

    /**
     * 단말을 수동 해지한다 — 대상이 없거나(존재하지 않는 토큰) 본인 소유가 아니면 조용히
     * 아무 것도 하지 않는다(멱등 204, API_SPEC §2.11 은 이 케이스의 전용 에러를 정의하지 않는다).
     */
    @Transactional
    public void revoke(Long accountId, String token) {
        deviceTokenRepository.findByAccountIdAndToken(accountId, token)
                .ifPresent(deviceToken -> deviceToken.revoke(OffsetDateTime.now(clock)));
    }
}
