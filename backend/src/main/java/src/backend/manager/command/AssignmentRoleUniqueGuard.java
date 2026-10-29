package src.backend.manager.command;

import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.ConstraintViolations;

/** 회차 배치 저장에서 나는 자리 중복 제약 위반을 {@code 409 DUPLICATE_ASSIGNMENT} 로 옮긴다. */
final class AssignmentRoleUniqueGuard {

    /**
     * 회차당 역할 하나를 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code assignment} 에 CHECK 와 FK 가 더 있기 때문이다 — 제약을 가리지
     * 않고 통째로 409 로 옮기면 존재하지 않는 회차·매니저를 가리킨 FK 위반까지 "이미 배치된
     * 역할입니다" 로 답한다.
     */
    private static final String ASSIGNMENT_ROLE_UNIQUE_CONSTRAINT = "uk_assignment_run_role";

    /**
     * 자리 중복을 {@code 409 DUPLICATE_ASSIGNMENT} 로 옮기며 배치 작업을 실행한다.
     *
     * <p>순차 요청은 {@code findByRunIdAndRole} 이 교체로 처리하므로 이 제약이 발동하는 것은
     * <b>경합뿐</b>이다 — 동시 2요청이 같은 자리를 채우려 하면 둘 다 "빈 자리" 를 읽고 각자 INSERT 를
     * 보낸다. 그 거부를 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와 "누가 먼저 채웠다" 가
     * 구별되지 않는다({@code BusCommandService} 가 같은 자리를 이미 푼다).
     *
     * <p><b>저장 호출까지 이 안에 넣어야 한다.</b> 신규 배치는 {@code IDENTITY} 키를 받으려고
     * {@code save()} 시점에 INSERT 가 이미 나가므로, {@code flush()} 만 감싸면 제약 위반이 이
     * {@code try} 밖에서 터진다 — 실제로 그 형태로 {@code DataIntegrityViolationException} 이 그대로
     * 새는 것을 동시성 테스트가 잡았다. 교체 경로는 변경 감지라 {@code flush()} 가 있어야 나가므로
     * <b>두 경로가 다르고</b>, 둘 다 덮으려면 저장과 flush 를 함께 감싸는 형태뿐이다.
     *
     * <p>{@link jakarta.persistence.EntityManager} 가 아니라 <b>저장소의</b> flush 를 부르는 것도
     * 같은 이유로 중요하다 — 예외 번역은 {@code @Repository} 빈을 거칠 때만 붙어,
     * {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를 그대로 지나친다.
     */
    static <T> T enforce(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isViolationOf(e, ASSIGNMENT_ROLE_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.DUPLICATE_ASSIGNMENT);
            }
            throw e;
        }
    }

    private AssignmentRoleUniqueGuard() {
    }
}
