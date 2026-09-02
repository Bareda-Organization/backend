package src.backend.notification.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.notification.dto.StaffNotificationItemResponse;
import src.backend.notification.dto.StaffNotificationListRequest;
import src.backend.notification.dto.StaffNotificationListResponse;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogQueryRepository;

/**
 * 관계자 웹의 알림 로그 전수 조회(API_SPEC §5.17, NTF-10·11, A-13) — 소속 학원 범위로만 좁힌다.
 *
 * <p>{@code date} 필터는 {@code sent_at}(없으면 {@code created_at})의 날짜 성분이다({@link
 * NotificationLogQueryRepository#search} javadoc 근거). 학원 자정 경계는 {@link Clock#getZone()}
 * (Asia/Seoul)으로 환산한다 — {@code ExceptionReportQueryService} 와 같은 관례다.
 *
 * <p>{@code unacked_count} 는 목록의 페이지·필터와 무관하게 학원 전체를 다시 센다({@link
 * NotificationLogQueryRepository#countByAcademyIdAndAckedFalse} javadoc 근거) — 배지 값이 필터를
 * 걸 때마다 달라지면 "확인 안 한 것이 몇 건인가" 라는 원래 의미를 잃는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffNotificationQueryService {

    /**
     * {@code date} 필터가 없을 때 넘길 극단 경계값 — {@code ExceptionReportQueryService} 와 같은 근거
     * (Postgres 가 단독 {@code OffsetDateTime IS NULL} 비교의 파라미터 타입을 추론하지 못한다).
     */
    private static final OffsetDateTime UNBOUNDED_FROM = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    private static final OffsetDateTime UNBOUNDED_TO = OffsetDateTime.parse("9999-12-31T23:59:59Z");

    private final NotificationLogQueryRepository notificationLogQueryRepository;

    private final Clock clock;

    /** 목록(§5.17) — {@code type}·{@code date}·{@code acked} 전부 선택적, 페이징 적용. */
    public StaffNotificationListResponse list(AuthUser requester, StaffNotificationListRequest request) {
        Long academyId = academyOf(requester);
        NotificationType type = ApiValues.notificationType(request.type());
        LocalDate date = ApiValues.date(request.date());
        Boolean acked = ApiValues.ackedFilter(request.acked());

        OffsetDateTime from = UNBOUNDED_FROM;
        OffsetDateTime to = UNBOUNDED_TO;
        if (date != null) {
            ZoneId zone = clock.getZone();
            from = date.atStartOfDay(zone).toOffsetDateTime();
            to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }

        PageParams pageParams = PageParams.of(request.page(), request.size());
        Page<NotificationLog> page = notificationLogQueryRepository.search(academyId, type, acked, from, to,
                PageRequest.of(pageParams.page(), pageParams.size()));

        List<StaffNotificationItemResponse> items = page.getContent().stream().map(this::toItem).toList();
        long unackedCount = notificationLogQueryRepository.countByAcademyIdAndAckedFalse(academyId);
        return StaffNotificationListResponse.of(page, items, unackedCount);
    }

    private StaffNotificationItemResponse toItem(NotificationLog log) {
        OffsetDateTime sentAt = log.getSentAt() != null ? log.getSentAt() : log.getCreatedAt();
        return new StaffNotificationItemResponse(log.getId(), sentAt, log.getBusNo(), log.getRecipientName(),
                lower(log.getRecipientRole().name()), lower(log.getType().name()), log.getBody(), log.isAcked());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** 요청 주체의 소속 학원 — 관계자 웹에는 "전 학원 로그" 화면이 부재하므로 특정하지 못하면 거부한다. */
    private Long academyOf(AuthUser requester) {
        return AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }
}
