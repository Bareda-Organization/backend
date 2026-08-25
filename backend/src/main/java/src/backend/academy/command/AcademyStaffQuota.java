package src.backend.academy.command;

import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 학원당 재직 관계자 1명 정원을 판정하는 <b>유일한 지점</b>(C-01 · ACAD-05·06).
 *
 * <p>가입 승인(API_SPEC §6.5)과 계정 재활성화(§6.7)가 각자 이 판정을 부른다. 두 곳에 복제되면 상한
 * 규칙이 한 번 바뀔 때 한쪽만 따라가고, 그 순간 정원이 새는데 <b>양쪽 테스트는 계속 통과한다</b> —
 * 각자 자기 사본을 보고 있기 때문이다.
 *
 * <p><b>선검사만으로는 부족하다.</b> 동시 요청 2건은 서로의 커밋을 보지 못한 채 둘 다 선검사를 지나고,
 * 그 뒤 DB 의 조건부 UNIQUE 가 하나를 거부한다. 그 거부를 옮기지 않으면 사용자에게 {@code 500} 이
 * 나가 "서버가 고장났다" 와 "정원이 찼다" 가 구별되지 않는다. 그래서 이 클래스가 선검사와 DB 거부 변환을
 * <b>함께</b> 들고 있다 — 둘 중 하나만 쓰는 호출부가 생기면 그 자리에서 다시 새기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class AcademyStaffQuota {

    /** 학원당 재직 관계자 상한(API_SPEC §6.5 "학원당 1명 유지"). */
    public static final int MAX_ACTIVE_STAFF_PER_ACADEMY = 1;

    /**
     * 정원을 강제하는 조건부 UNIQUE 인덱스 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code academy_staff} 에 UNIQUE 가 하나 더 있기 때문이다
     * ({@code uk_academy_staff_account} — 한 계정이 두 학원의 관계자를 겸하는 것을 막는다). 제약을
     * 가리지 않고 {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면, 계정 중복 연결까지
     * "정원이 찼습니다" 로 응답해 원인을 감춘다.
     */
    private static final String QUOTA_UNIQUE_INDEX = "uk_academy_staff_academy_active";

    private final AcademyStaffRepository academyStaffRepository;

    private final EntityManager entityManager;

    /**
     * 정원 판정을 통과시킨 뒤 관계자 행을 바꾸는 작업을 실행한다.
     *
     * <p>{@code action} 실행 후 즉시 flush 하는 것이 이 메서드의 핵심이다 — flush 하지 않으면 INSERT 가
     * 커밋 시점까지 미뤄져 제약 위반이 이 {@code try} 밖에서 터지고, 그때는 이미 응답 변환 경로를
     * 지나쳐 {@code 500} 이 나간다.
     *
     * @param academyId 정원을 판정할 학원
     * @param action    관계자 행을 만들거나 {@code active} 로 되돌리는 작업
     * @throws BusinessException {@code STAFF_QUOTA_EXCEEDED} — 선검사에서든 DB 거부에서든 같은 코드다
     */
    public <T> T enforce(Long academyId, Supplier<T> action) {
        assertWithinQuota(academyId);
        try {
            T result = action.get();
            entityManager.flush();
            return result;
        } catch (DataIntegrityViolationException e) {
            if (isQuotaViolation(e)) {
                throw new BusinessException(ErrorCode.STAFF_QUOTA_EXCEEDED);
            }
            throw e;
        }
    }

    /** 재직자 수를 세어 상한과 견준다 — 퇴사 행은 세지 않으므로 교체는 막히지 않는다(Ruling 139). */
    private void assertWithinQuota(Long academyId) {
        long activeStaff = academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE);
        if (activeStaff >= MAX_ACTIVE_STAFF_PER_ACADEMY) {
            throw new BusinessException(ErrorCode.STAFF_QUOTA_EXCEEDED);
        }
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 정원 인덱스인지만 본다. */
    private boolean isQuotaViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && QUOTA_UNIQUE_INDEX.equals(cve.getConstraintName());
    }
}
