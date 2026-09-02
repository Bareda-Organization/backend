package src.backend.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.controller.NotificationLogFixtures;
import src.backend.notification.controller.StaffNotificationFixtures;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 좌석 경계 시험 — <b>쓰는 쪽(T2 읽음 처리)과 세는 쪽(T3 미확인 배지)을 한 시험에서 잇는다.</b>
 *
 * <p>왜 별도 클래스인가 — 두 기능이 서로 다른 격리 워크트리에서 만들어져 <b>병합 전에는 한쪽 코드가
 * 다른 쪽 트리에 물리적으로 없었다.</b> 그래서 어느 좌석도 이 시험을 만들 수 없었고 조율자 몫으로
 * 이관됐다(Ruling 222). Phase 9 의 {@code Phase9CrossSeatWiringTest} 와 같은 자리다.
 *
 * <p>🔴 <b>이 시험이 실제로 결함을 잡았다.</b> 병합 시점에 쓰는 쪽은 중요 통지 3종에만
 * {@code acked} 를 남기는데 세는 쪽은 <b>전 종류</b>를 세고 있었다 — 승하차·도착 알림은 영원히
 * {@code acked=false} 라 미확인 배지가 0 이 되지 않고 발송할 때마다 단조 증가했다(Ruling 227).
 * <b>양쪽 좌석의 시험은 각자 전건 통과였다</b> — 각자 자기 쪽만 검사했기 때문이다.
 *
 * <p>대상 집합은 {@link NotificationType#IMPORTANT_FOR_ACK} 한 곳에서만 정의하고 양쪽이 참조한다.
 * 그 상수를 넓히거나 좁히면 이 시험의 두 메서드가 함께 움직인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase12AckBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("경계 — 학부모가 중요 통지를 실제로 읽음 처리하면 관계자 미확인 배지가 줄어든다")
    void 실제_읽음_처리가_관계자_미확인_배지를_줄인다() throws Exception {
        NotificationLogFixtures logs = logFixtures();
        long academyId = logs.academy();
        long parentId = logs.account(academyId, "보호자1", Role.PARENT);
        long staffId = staffFixtures().staffAccount(academyId, "관계자1");

        // 중요 통지(지연) 1건 — 이것만 읽음 처리로 acked 가 된다.
        long delayId = logs.notification(academyId, parentId, "보호자1", Role.PARENT, null, null,
                NotificationType.DELAY, "지연 안내", "출발이 지연됩니다", false, now(), now(), null);

        미확인_배지(staffId, academyId, 1);

        mockMvc.perform(patch("/api/v1/notifications/" + delayId + "/read")
                .header("Authorization", 토큰(parentId, academyId, Role.PARENT)))
                .andExpect(status().isNoContent());

        미확인_배지(staffId, academyId, 0);
    }

    @Test
    @DisplayName("경계 — 확인 추적 대상이 아닌 종류는 미확인 배지에 잡히지 않는다")
    void 확인_대상_밖_종류는_배지에_잡히지_않는다() throws Exception {
        NotificationLogFixtures logs = logFixtures();
        long academyId = logs.academy();
        long parentId = logs.account(academyId, "보호자2", Role.PARENT);
        long staffId = staffFixtures().staffAccount(academyId, "관계자2");

        // 승차·하차·도착은 읽음 처리를 거쳐도 acked 가 남지 않는 종류다. 배지가 이들을 세면
        // 영원히 0 이 되지 않는다 — Ruling 227 이 잡은 결함의 형태다.
        logs.notification(academyId, parentId, "보호자2", Role.PARENT, null, null, NotificationType.BOARDING,
                "승차 안내", "승차했습니다", false, now(), now(), null);
        logs.notification(academyId, parentId, "보호자2", Role.PARENT, null, null, NotificationType.ALIGHTING,
                "하차 안내", "하차했습니다", false, now(), now(), null);
        logs.notification(academyId, parentId, "보호자2", Role.PARENT, null, null, NotificationType.ARRIVE,
                "도착 안내", "곧 도착합니다", false, now(), now(), null);

        미확인_배지(staffId, academyId, 0);
    }

    private void 미확인_배지(long staffAccountId, long academyId, int 기대값) throws Exception {
        mockMvc.perform(get("/api/v1/staff/notifications")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unacked_count").value(기대값));
    }

    private NotificationLogFixtures logFixtures() {
        return new NotificationLogFixtures(academyRepository, accountRepository, jdbcTemplate);
    }

    private StaffNotificationFixtures staffFixtures() {
        return new StaffNotificationFixtures(academyRepository, accountRepository, academyStaffRepository,
                notificationLogRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
