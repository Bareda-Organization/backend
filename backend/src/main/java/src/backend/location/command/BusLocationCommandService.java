package src.backend.location.command;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.BusLocationReportRequest;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.event.BusLocationUpdatedEvent;
import src.backend.location.repository.spec.BusLocationRepository;

/**
 * 버스 단위 실시간 위치 입력 — 학생 단위 {@link LocationCommandService}와 나란한 미러(F1).
 * 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * {@code ingest()} 는 저장 직후 {@link BusLocationUpdatedEvent}를 발행해(AFTER_COMMIT → Kafka)
 * 관제·학부모 실시간 push({@code location.projection.BusLocationPushConsumer})를 깨운다.
 */
@Service
public class BusLocationCommandService {

    private final BusLocationRepository busLocationRepository;
    private final BusRepository busRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BusLocationCommandService(BusLocationRepository busLocationRepository,
                                     BusRepository busRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.busLocationRepository = busLocationRepository;
        this.busRepository = busRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void reportSelf(AuthUser driver, BusLocationReportRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 보고할 수 있습니다");
        }
        ingest(bus.getTenant().getId(), req.busId(), req.lat(), req.lng(), LocationOrigin.GPS);
    }

    @Transactional
    public void ingest(Long tenantId, Long busId, double lat, double lng, LocationOrigin origin) {
        LocalDateTime recordedAt = LocalDateTime.now();
        busLocationRepository.save(new BusLocationPing(busId, tenantId, lat, lng, recordedAt, origin));
        eventPublisher.publishEvent(BusLocationUpdatedEvent.of(tenantId, busId, lat, lng, origin, recordedAt));
    }
}
