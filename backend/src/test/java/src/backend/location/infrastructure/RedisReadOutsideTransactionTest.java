package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.clock.SeedDateClockConfig;

/**
 * 위치 읽기 3종(관계자 관제 · 메인 관리자 관제 · 학부모 버스 위치)은 Redis 를 부르는 순간 DB 트랜잭션이 없다(R46
 * D #13) — Redis 응답이 늦는 동안 DB 연결을 쥐고 있지 않게 한다. {@link RunPositionStore} 를 감시해 호출 시점의
 * 트랜잭션 활성 여부를 기록한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeedDateClockConfig.class)
class RedisReadOutsideTransactionTest {

    private static final long ACADEMY_A = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoSpyBean
    private RunPositionStore runPositionStore;

    @Test
    @DisplayName("위치 읽기 3종은 Redis(RunPositionStore) 호출 시점에 트랜잭션이 없다")
    void 위치_읽기_3종은_Redis_호출_시점에_트랜잭션이_없다() throws Exception {
        List<Boolean> activeAtCall = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            activeAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(runPositionStore).findAll(any(Collection.class));
        Mockito.doAnswer(invocation -> {
            activeAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(runPositionStore).find(any());

        호출한다("/api/v1/staff/runs/live", 토큰(2L, ACADEMY_A, Role.STAFF));
        호출한다("/api/v1/admin/academies/%d/runs/live".formatted(ACADEMY_A), 토큰(1L, null, Role.SYSTEM_ADMIN));
        호출한다("/api/v1/students/2/bus-position", 토큰(5L, ACADEMY_A, Role.PARENT));

        assertThat(activeAtCall).as("3종의 Redis 호출이 모두 관측돼야 한다").hasSizeGreaterThanOrEqualTo(3).containsOnly(false);
    }

    private void 호출한다(String path, String token) throws Exception {
        mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
    }

    private String 토큰(long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
