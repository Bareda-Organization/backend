package src.backend.notification.query;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationItemResponse;
import src.backend.notification.dto.NotificationListResponse;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 알림 목록 조회(API_SPEC §3.12, NTF-08 · P-09 · S-03) — 전 역할 공통, 소속 학원 + 본인 계정으로 좁힌다.
 *
 * <p>보관 14일은 {@code createdAt} 기준이다({@link NotificationLogRepository#search} javadoc 근거 —
 * {@code sentAt} 은 미발송·설정 off 로 널일 수 있다). 경계는 <b>포함</b>이다 — 정확히 14일 전 순간까지는
 * 아직 보인다({@code now.minusDays(14)} 이상). 스펙이 경계 방향을 명시하지 않아 이 서비스의 판단이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    /** 보관 기간(API_SPEC §3.12) — 이 값보다 오래된 알림은 목록·배지 어디에도 나타나지 않는다. */
    private static final long RETENTION_DAYS = 14;

    /** {@code sent_at} 이 널일 수 있어(§3.12) 정렬 축은 {@code created_at} 하나다 — 선택 정렬 부재. */
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final NotificationLogRepository notificationLogRepository;

    private final Clock clock;

    /** 목록(§3.12) — {@code type}·{@code unreadOnly} 는 {@code items[]} 만 좁히고, {@code unreadCount} 는 항상 전체 기준이다. */
    public NotificationListResponse list(AuthUser requester, String type, Boolean unreadOnly, Integer pageParam,
            Integer sizeParam) {
        NotificationType parsedType = ApiValues.notificationType(type);
        boolean unreadOnlyFlag = unreadOnly != null && unreadOnly;
        OffsetDateTime retentionFrom = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
        Pageable pageable = PageParams.of(pageParam, sizeParam).toPageable(SORT);

        Page<NotificationLog> page = notificationLogRepository.search(requester.academyId(), requester.accountId(),
                parsedType, unreadOnlyFlag, retentionFrom, pageable);
        long unreadCount = notificationLogRepository.countUnread(requester.academyId(), requester.accountId(),
                retentionFrom);

        PageResponse<NotificationItemResponse> items = PageResponse.of(page,
                page.getContent().stream().map(NotificationItemResponse::of).toList());
        return NotificationListResponse.of(items, unreadCount);
    }
}
