package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.command.RouteCommandService;
import src.backend.routing.command.RouteOptimizeService;
import src.backend.routing.domain.RouteStopLimit;
import src.backend.routing.dto.RouteOptimizeRequest;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteStopsSaveRequest;
import src.backend.routing.dto.RouteUpdateRequest;

/**
 * 노선 하나의 정차지는 최대 50개다(R46 · Ruling 613) — 상한이 없으면 정차지 500개짜리 노선 하나를 열 때마다 외부 경로
 * 호출이 32회 나가 일일 한도(3,000)를 갉아먹고 요청 스레드를 수 분 묶는다. 등록 · 수정 · 정차지 저장 · 최적화 고정 정차지 4곳의
 * 요청 DTO 가 {@code 422} 로 막고, 서비스도 DTO 를 거치지 않는 호출에 같은 상한을 건다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RouteStopLimitTest {

    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long BUS_A_ID = 2L;

    private static final int LIMIT = 50;

    private static final String LIMIT_MESSAGE = "최대 50개";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private jakarta.validation.Validator validator;

    @Autowired
    private RouteCommandService routeCommandService;

    @Autowired
    private RouteOptimizeService routeOptimizeService;

    @Test
    @DisplayName("등록 — stop_ids 가 51개면 422 VALIDATION_FAILED")
    void 등록은_정차지_51개를_거절한다() throws Exception {
        assertValidationFailed(mockMvc.perform(post("/api/v1/staff/routes").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bus_id\":%d,\"weekday\":\"thu\",\"direction\":\"to_academy\",\"stop_ids\":%s}"
                        .formatted(BUS_A_ID, ids(LIMIT + 1)))));
    }

    @Test
    @DisplayName("수정 — stop_ids 가 51개면 422 VALIDATION_FAILED")
    void 수정은_정차지_51개를_거절한다() throws Exception {
        assertValidationFailed(mockMvc.perform(patch("/api/v1/staff/routes/1").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"stop_ids\":%s}".formatted(ids(LIMIT + 1)))));
    }

    @Test
    @DisplayName("정차지 저장 — stops 가 51개면 422 VALIDATION_FAILED")
    void 정차지_저장은_51개를_거절한다() throws Exception {
        String items = LongStream.rangeClosed(1, LIMIT + 1)
                .mapToObj(i -> "{\"name\":\"정차%d\",\"lat\":37.5,\"lng\":127.0}".formatted(i))
                .collect(Collectors.joining(",", "[", "]"));
        assertValidationFailed(mockMvc.perform(put("/api/v1/staff/routes/1/stops").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"stops\":%s}".formatted(items))));
    }

    @Test
    @DisplayName("최적화 — fixed_stop_ids 가 51개면 422 VALIDATION_FAILED")
    void 최적화는_고정_정차지_51개를_거절한다() throws Exception {
        assertValidationFailed(mockMvc.perform(post("/api/v1/staff/routes/1/optimize").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixed_stop_ids\":%s}".formatted(ids(LIMIT + 1)))));
    }

    @Test
    @DisplayName("서비스 — DTO 를 거치지 않는 호출도 51개면 VALIDATION_FAILED (경로·버스 조회보다 먼저)")
    void 서비스도_같은_상한을_건다() {
        AuthUser staff = new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
        List<Long> tooMany = LongStream.rangeClosed(1, LIMIT + 1).boxed().toList();
        List<RouteStopsSaveRequest.Item> items = tooMany.stream()
                .map(i -> new RouteStopsSaveRequest.Item(null, "정차" + i, null, BigDecimal.ONE, BigDecimal.ONE)).toList();

        assertValidationFailed(() -> routeCommandService.register(staff,
                new RouteRegisterRequest(BUS_A_ID, "thu", "to_academy", null, null, tooMany)));
        assertValidationFailed(() -> routeCommandService.update(staff, 999_999L,
                new RouteUpdateRequest(null, null, null, null, null, tooMany)));
        assertValidationFailed(() -> routeCommandService.saveStops(staff, 999_999L, new RouteStopsSaveRequest(items)));
        assertValidationFailed(() -> routeOptimizeService.optimize(staff, 999_999L,
                new RouteOptimizeRequest(null, null, tooMany)));
    }

    /**
     * BR-362 — 상한 <b>바로 아래</b>(정확히 50개)가 서비스를 통과한다. 51개 거절만 보면 서비스 검사가 {@code >} 에서 {@code >=} 로 어긋나
     * 정확히 50개 편성이 {@code 422} 가 되어도 초록이다. 새 정차지 50개(서로 50m 넘게 떨어진 점)를 저장하는 경로를 서비스까지 흘려
     * 노선에 50개가 실제로 담기는 것까지 본다.
     */
    @Test
    @DisplayName("서비스 — 정확히 50개는 통과해 노선에 50개가 담긴다")
    void 서비스는_정확히_50개를_통과시킨다() {
        AuthUser staff = new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
        long routeId = routeCommandService
                .register(staff, new RouteRegisterRequest(BUS_A_ID, "sun", "from_academy", null, null, List.of())).id();
        // 위도 0.001° 간격(약 111m)이라 새 승하차지가 50m 안 기존 승하차지로 합쳐지지 않는다
        List<RouteStopsSaveRequest.Item> fifty = LongStream.rangeClosed(1, LIMIT)
                .mapToObj(i -> new RouteStopsSaveRequest.Item(null, "한계정차" + i, null,
                        BigDecimal.valueOf(36.0 + i * 0.001), BigDecimal.valueOf(127.0)))
                .toList();

        assertThat(routeCommandService.saveStops(staff, routeId, new RouteStopsSaveRequest(fifty)).stops())
                .as("정확히 50개는 상한 안이다").hasSize(LIMIT);
    }

    @Test
    @DisplayName("상한 검사 — 50개는 통과하고 51개부터 거절한다(경계 양쪽)")
    void 상한_검사는_50개를_통과시키고_51개를_거절한다() {
        RouteStopLimit.assertWithin(LongStream.rangeClosed(1, LIMIT).boxed().toList());

        assertValidationFailed(() -> RouteStopLimit.assertWithin(LongStream.rangeClosed(1, LIMIT + 1).boxed().toList()));
    }

    @Test
    @DisplayName("요청 DTO 4종 — 빈 검증이 51개를 위반으로 보고 50개는 통과시킨다(서비스 검증과 별개로)")
    void 요청_DTO_검증은_51개를_위반으로_본다() {
        List<Long> fifty = LongStream.rangeClosed(1, LIMIT).boxed().toList();
        List<Long> fiftyOne = LongStream.rangeClosed(1, LIMIT + 1).boxed().toList();
        List<RouteStopsSaveRequest.Item> itemsOf51 = fiftyOne.stream()
                .map(i -> new RouteStopsSaveRequest.Item(null, "정차" + i, null, BigDecimal.ONE, BigDecimal.ONE)).toList();

        assertThat(violations(new RouteRegisterRequest(BUS_A_ID, "thu", "to_academy", null, null, fiftyOne))).hasSize(1);
        assertThat(violations(new RouteUpdateRequest(null, null, null, null, null, fiftyOne))).hasSize(1);
        assertThat(violations(new RouteStopsSaveRequest(itemsOf51))).hasSize(1);
        assertThat(violations(new RouteOptimizeRequest(null, null, fiftyOne))).hasSize(1);
        assertThat(violations(new RouteRegisterRequest(BUS_A_ID, "thu", "to_academy", null, null, fifty))).isEmpty();
        assertThat(violations(new RouteUpdateRequest(null, null, null, null, null, fifty))).isEmpty();
        assertThat(violations(new RouteOptimizeRequest(null, null, fifty))).isEmpty();
    }

    private <T> java.util.Set<jakarta.validation.ConstraintViolation<T>> violations(T request) {
        return validator.validate(request);
    }

    private void assertValidationFailed(ResultActions result) throws Exception {
        // 같은 422 라도 "없는 승하차지"·"중복" 같은 다른 사유가 아니라 상한 때문이어야 한다 — 문구로 가른다
        result.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value(containsString(LIMIT_MESSAGE)));
    }

    private void assertValidationFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getMessage()).contains(LIMIT_MESSAGE);
        });
    }

    private static String ids(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private String token() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
