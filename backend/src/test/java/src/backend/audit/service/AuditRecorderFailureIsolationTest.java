package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 적재가 실패해도 조회 트랜잭션은 성공한다(Ruling 242 · BR-038) — {@link AuditRecorder} 자바독의 약속.
 *
 * <p>실패를 가짜 응답 객체로 흉내 내지 않고 <b>실제 INSERT 를 실패</b>시킨다({@code target_type} 은
 * {@code varchar(50)}). 저장소의 트랜잭션 경계 안에서 난 예외만 트랜잭션에 롤백 표시를 남기므로, 가짜로
 * 던지면 결함 경로를 지나지 않는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 조회 서비스처럼 읽기 전용 트랜잭션을 직접 열어 그 커밋을 본다.
 */
@SpringBootTest
class AuditRecorderFailureIsolationTest {

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 감사_적재가_실패해도_조회_트랜잭션은_예외_없이_끝난다() {
        TransactionTemplate readOnlyQuery = new TransactionTemplate(transactionManager);
        readOnlyQuery.setReadOnly(true);
        String tooLongTargetType = "t".repeat(51);

        assertThatCode(() -> readOnlyQuery.executeWithoutResult(status ->
                auditRecorder.recordDataAccessRead(1L, 2L, tooLongTargetType, 1L, Map.of())))
                .doesNotThrowAnyException();
    }
}
