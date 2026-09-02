package src.backend.admin.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.admin.dto.AdminEmergencyItemResponse;
import src.backend.admin.dto.AdminEmergencyListResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.repository.EmergencyAlertRepository;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 조회(Phase 11 T2 목표 11) — {@code emergency_alert} 를
 * {@code academy_id} 로 좁히지 않는 유일한 읽기 경로다({@link EmergencyAlertRepository
 * #findAllByOrderByReceivedAtDesc} 의 {@code @AcademyScopeExempt} 참고).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminEmergencyQueryService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final AcademyRepository academyRepository;

    private final Clock clock;

    public AdminEmergencyListResponse list() {
        List<EmergencyAlert> alerts = emergencyAlertRepository.findAllByOrderByReceivedAtDesc();

        Map<Long, Academy> academiesById = academyRepository
                .findAllById(alerts.stream().map(EmergencyAlert::getAcademyId).toList())
                .stream()
                .collect(Collectors.toMap(Academy::getId, a -> a));

        OffsetDateTime now = OffsetDateTime.now(clock);

        List<AdminEmergencyItemResponse> items = alerts.stream()
                .map(alert -> toItem(alert, academiesById.get(alert.getAcademyId()), now))
                .toList();

        long unackedCount = items.stream().filter(item -> !item.staffAcked() && item.canceledAt() == null).count();

        return new AdminEmergencyListResponse(items, unackedCount);
    }

    /**
     * 경과 시간(목표 11)의 종료 시점 — 취소·확인으로 이미 끝난 신고는 그 시각에서 멈춘다({@link
     * AdminEmergencyItemResponse} 자바독). 아직 열려 있는 신고만 {@code now} 까지 계속 흐른다.
     */
    private AdminEmergencyItemResponse toItem(EmergencyAlert alert, Academy academy, OffsetDateTime now) {
        OffsetDateTime elapsedEnd = alert.getCanceledAt() != null ? alert.getCanceledAt()
                : alert.getAckedAt() != null ? alert.getAckedAt() : now;
        long elapsedSeconds = Duration.between(alert.getReceivedAt(), elapsedEnd).getSeconds();

        AdminEmergencyItemResponse.AcademyInfo academyInfo = academy == null ? null
                : new AdminEmergencyItemResponse.AcademyInfo(academy.getId(), academy.getName(),
                        academy.getContact());

        return new AdminEmergencyItemResponse(alert.getId(), academyInfo, alert.getRunId(), alert.getBusNo(),
                alert.getType().name(), alert.getMemo(), alert.getLat(), alert.getLng(), alert.getRiderCount(),
                alert.getOccurredAt(), alert.getReceivedAt(), alert.isAcked(), alert.getAckedAt(),
                alert.getCanceledAt(), elapsedSeconds);
    }
}
