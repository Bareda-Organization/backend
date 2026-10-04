package src.backend.notification.query;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.notification.dto.StaffNotificationGroupResponse;
import src.backend.notification.dto.StaffNotificationItemResponse;
import src.backend.notification.dto.StaffNotificationListRequest;
import src.backend.notification.dto.StaffNotificationListResponse;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 관계자 웹의 알림 로그 전수 조회(API_SPEC §5.17, NTF-10·11, A-13) — 소속 학원 범위로만 좁힌다.
 *
 * <p>{@code date} 필터는 {@code sent_at}(없으면 {@code created_at})의 날짜 성분이다({@link
 * NotificationLogRepository#searchForStaffLog} javadoc 근거). 학원 자정 경계는 {@link Clock#getZone()}
 * (Asia/Seoul)으로 환산한다 — {@code ExceptionReportQueryService} 와 같은 관례다.
 *
 * <p>{@code unacked_count} 는 목록의 페이지·필터와 무관하게 학원 전체를 다시 센다({@link
 * NotificationLogRepository#countUnackedForStaffLog} javadoc 근거) — 배지 값이 필터를
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

    /** 묶음 쿼리가 수신자 앞 3명을 이어 붙일 때 쓰는 구분자(ASCII 30 = 수신자 사이, 31 = 이름과 역할 사이 — 이름에 들어갈 수 없다). */
    private static final String RECIPIENT_SEPARATOR = "\u001e";

    private static final String FIELD_SEPARATOR = "\u001f";

    private final NotificationLogRepository notificationLogRepository;

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

        Role recipientRole = request.recipientRole() == null ? null : ApiValues.recipientRole(request.recipientRole());
        PageParams pageParams = PageParams.of(request.page(), request.size());
        long unackedCount = notificationLogRepository.countUnackedForStaffLog(
                academyId, NotificationType.IMPORTANT_FOR_ACK);
        if (Boolean.TRUE.equals(ApiValues.groupFilter(request.group()))) {
            return groupedList(academyId, type, recipientRole, acked, from, to, pageParams, unackedCount);
        }
        Page<NotificationLog> page = notificationLogRepository.searchForStaffLog(academyId, type, acked,
                NotificationType.IMPORTANT_FOR_ACK, recipientRole, from, to,
                PageRequest.of(pageParams.page(), pageParams.size()));

        List<StaffNotificationItemResponse> items = page.getContent().stream().map(this::toItem).toList();
        return StaffNotificationListResponse.of(page, items, unackedCount);
    }

    /**
     * 묶어 보기(Ruling 813) — 같은 사건(같은 type · run_id · body · 적재 시각 초)의 행을 한 항목으로 묶고 쪽 나누기와
     * {@code total_count} 를 묶음 단위로 센다. 쪽 안에서만 묶으면 쪽 경계에서 같은 알림이 갈린다.
     */
    private StaffNotificationListResponse groupedList(Long academyId, NotificationType type, Role recipientRole,
            Boolean acked, OffsetDateTime from, OffsetDateTime to, PageParams pageParams, long unackedCount) {
        String typeCode = type == null ? null : lower(type.name());
        String roleCode = recipientRole == null ? null : lower(recipientRole.name());
        List<String> importantTypes = NotificationType.IMPORTANT_FOR_ACK.stream().map(t -> lower(t.name())).toList();
        long total = notificationLogRepository.countGroupsForStaffLog(academyId, typeCode, roleCode, acked,
                importantTypes, from, to);
        List<StaffNotificationGroupResponse> items = notificationLogRepository
                .searchGroupsForStaffLog(academyId, typeCode, roleCode, acked, importantTypes, from, to,
                        pageParams.size(), (long) pageParams.page() * pageParams.size())
                .stream().map(this::toGroup).toList();
        return StaffNotificationListResponse.ofGroups(pageParams.page(), pageParams.size(), total, items,
                unackedCount);
    }

    /** 묶음 한 줄 — 키는 구성 값(type · run_id · 초 · body)에서 만든다(같은 사건이면 언제 읽어도 같다). */
    private StaffNotificationGroupResponse toGroup(NotificationLogRepository.StaffLogGroup row) {
        OffsetDateTime sentAt = Instant.EPOCH.plus(row.getSentAtMicros(), ChronoUnit.MICROS)
                .atZone(clock.getZone()).toOffsetDateTime();
        String groupKey = row.getType() + ":" + row.getRunId() + ":" + row.getBucketSecond() + ":"
                + UUID.nameUUIDFromBytes(row.getBody().getBytes(StandardCharsets.UTF_8));
        List<StaffNotificationGroupResponse.Recipient> recipients = Arrays.stream(row.getFirstRecipients().split(RECIPIENT_SEPARATOR))
                .map(pair -> pair.split(FIELD_SEPARATOR, 2))
                .map(pair -> new StaffNotificationGroupResponse.Recipient(pair[0], pair[1]))
                .toList();
        return new StaffNotificationGroupResponse(groupKey, sentAt, row.getBusNo(), row.getType(), row.getBody(),
                row.getRecipientCount(), row.getAckedCount(), recipients);
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
