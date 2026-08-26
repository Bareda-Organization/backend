package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@code dedup_key} 를 <b>이벤트가 실어 온 판정 시각</b>으로 조립하는지 고정한다 — 소비 시점 시계로
 * 만들면 같은 이벤트가 재배달될 때마다 다른 키가 생겨 <b>적재 축의 UNIQUE 가 통째로 무력해진다.</b>
 *
 * <p>아웃박스의 존재 이유가 걸린 자리다. 재배달은 유실을 막으려고 <b>일부러</b> 만드는 성질인데
 * (커밋 직후 죽은 앱을 워커가 다시 집는 구조가 그것이다), 키가 배달 시각을 따라 움직이면 재시도가
 * 곧 중복 발송이 된다.
 *
 * <p><b>판정 시각을 과거로 못박은 것이 이 클래스의 결정성이다.</b> "행이 1건이다" 만 단언하면 소비
 * 시점 시계를 쓰는 구현도 두 배달 사이에 시각이 안 바뀌는 빠른 실행에서는 통과한다. 그래서
 * ①키를 <b>직접 읽어</b> 그 과거 시각과 대조하고 ②배달 사이에 <b>시계가 실제로 나아간 것을 확인</b>한
 * 뒤 두 번째를 보낸다. 둘 중 하나만으로는 이 축이 열린다.
 */
@SpringBootTest
class SignupDecidedDedupKeyTest {

    /**
     * 못박은 판정 시각 — <b>실행 시점과 확실히 다른 과거</b>여야 한다. "지금" 근처를 쓰면 소비 시점
     * 시계를 쓰는 구현이 우연히 같은 값을 만들어 단언이 아무것도 가리지 못한다.
     */
    private static final OffsetDateTime 판정_시각 = OffsetDateTime.parse("2020-01-02T03:04:05.123456+09:00");

    /** 시드 {@code notification_log} 에 없는 계정 — 이 테이블은 FK 가 부재해(ERD §4.2) 실재하지 않아도 된다. */
    private static final long 신청자_계정 = 4004L;

    private static final String 기대_키 = "signup_decided:na:" + 신청자_계정 + ":" + 판정_시각;

    private static final long 시계_대기_상한_초 = 5;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE recipient_account_id = ?", 신청자_계정);
    }

    @Test
    void 같은_이벤트를_두_번_배달해도_dedup_key_가_같다() {
        List<Optional<ErrorCode>> 결과 = 두_번_배달한다();

        assertThat(적재된_키())
                .as("키가 이벤트의 판정 시각이 아니라 배달 시각을 따라가면 재배달마다 새 키가 생긴다")
                .isEqualTo(기대_키);
        assertThat(결과.get(1))
                .as("두 번째 배달이 중복으로 막히지 않았다는 것은 그 배달이 <b>다른 키</b>를 만들었다는 뜻이다")
                .contains(ErrorCode.DUPLICATE_NOTIFICATION);
    }

    @Test
    void 같은_이벤트를_두_번_배달해도_notification_log_행이_1건이다() {
        두_번_배달한다();

        assertThat(적재된_행_수())
                .as("행이 2건이면 수신자는 같은 결정을 두 번 통지받는다 — 아웃박스 재시도가 곧 중복 발송이 된다")
                .isEqualTo(1);
    }

    /**
     * 같은 이벤트를 두 번 배달한다 — 사이에 <b>시계가 실제로 나아간 것</b>을 확인한다.
     *
     * <p>그 확인이 없으면 두 배달이 같은 마이크로초에 떨어질 수 있고, 그때는 소비 시점 시계를 쓰는
     * 구현도 두 키가 같아 통과한다.
     */
    private List<Optional<ErrorCode>> 두_번_배달한다() {
        SignupDecidedEvent event = new SignupDecidedEvent(1L, 신청자_계정, "조대기", Role.PARENT, false,
                "제출 서류가 부족합니다", 판정_시각);
        Optional<ErrorCode> 첫째 = 배달한다(event);
        시계가_나아갈_때까지_기다린다();
        return List.of(첫째, 배달한다(event));
    }

    /** 발행측이 하는 일과 같다 — 트랜잭션 안에서 이벤트만 던진다. 적재는 구독자가 한다. */
    private Optional<ErrorCode> 배달한다(SignupDecidedEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
            return Optional.empty();
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    /**
     * 시계가 한 눈금이라도 나아갈 때까지 기다린다 — 고정 대기가 아니라 <b>값을 물어</b> 기다린다.
     *
     * <p>상한에 걸리면 예외로 드러낸다. 조용히 지나가면 그 실행의 두 단언은 아무것도 검증하지
     * 못한 채 초록이 되고, 그것은 실패보다 나쁘다.
     */
    private void 시계가_나아갈_때까지_기다린다() {
        OffsetDateTime 기준 = OffsetDateTime.now(clock);
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(시계_대기_상한_초);
        while (System.nanoTime() < 마감) {
            if (OffsetDateTime.now(clock).isAfter(기준)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("시계가 나아가지 않았다 — 이 대기 없이는 소비 시점 시계를 쓰는 구현도 통과한다");
    }

    private String 적재된_키() {
        return jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM notification_log WHERE recipient_account_id = ? ORDER BY id LIMIT 1",
                String.class, 신청자_계정);
    }

    private int 적재된_행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                신청자_계정);
    }
}
