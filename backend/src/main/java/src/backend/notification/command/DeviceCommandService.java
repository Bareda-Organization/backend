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
import src.backend.notification.entity.DeviceToken;
import src.backend.notification.repository.DeviceTokenRepository;

/** 푸시 단말 등록·해지(NTF-12, API_SPEC §2.11). */
@Service
@RequiredArgsConstructor
public class DeviceCommandService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final Clock clock;

    /**
     * 단말을 등록한다 — 같은 {@code (accountId, deviceId)} 행이 이미 있으면 삭제 후 새로 만든다
     * ({@code DeviceToken} Javadoc "값 수정이 아니라 행 대체"). DB UNIQUE 제약이 있어 update 로
     * 흉내내지 않고 실제로 지웠다 다시 심어야, 같은 기기를 두 번 등록해도 행이 늘지 않는다.
     */
    @Transactional
    public DeviceRegisterResponse register(Long accountId, DeviceRegisterRequest request) {
        deviceTokenRepository.findByAccountIdAndDeviceId(accountId, request.deviceId())
                .ifPresent(deviceTokenRepository::delete);
        deviceTokenRepository.flush();

        DevicePlatform platform = DevicePlatform.valueOf(request.platform().toUpperCase(Locale.ROOT));
        DeviceToken deviceToken = DeviceToken.register(accountId, request.deviceId(), request.token(), platform,
                request.appVersion());
        deviceTokenRepository.save(deviceToken);

        return new DeviceRegisterResponse(deviceToken.getDeviceId(), deviceToken.getCreatedAt());
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
