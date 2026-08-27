package src.backend.student.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.student.domain.VerifiedAddressEntry;
import src.backend.student.domain.VerifiedAddressEntry.AddressSlot;
import src.backend.student.entity.WeeklyAddress;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 검증을 마친 칸들을 요일 × 방향 단위로 적재한다(P-05 · STU-05·06, API_SPEC §3.7).
 *
 * <p><b>이 클래스가 트랜잭션 경계이고, 여기에는 {@code GeocodingClient} 가 없다</b> — 외부 호출은
 * {@link AddressVerification} 이 트랜잭션 밖에서 끝낸 뒤 결과만 넘어온다(§7 규칙 16). 주입 자체가
 * 없어야 "검증을 여기로 옮겨 오는" 변경이 <b>컴파일되지 않는다.</b>
 */
@Component
@RequiredArgsConstructor
public class WeeklyAddressStore {

    /**
     * 요일 × 방향 조합당 1행을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가려 번역하는 이유는 이 저장이 {@code weekly_address} 의 FK 두 개
     * ({@code fk_weekly_address_student}·{@code fk_weekly_address_stop})도 함께 지나기 때문이다.
     * 제약을 가리지 않고 {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 사라진
     * 학생·승하차지를 가리킨 저장까지 "요일·방향이 중복됐다" 로 응답해 원인을 감춘다.
     */
    private static final String WEEKDAY_DIRECTION_UNIQUE_CONSTRAINT = "uk_weekly_address_student_weekday_direction";

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final StopMatcher stopMatcher;

    private final Clock clock;

    /**
     * 보낸 칸만 반영하고 나머지는 그대로 둔다 — 같은 칸을 다시 보내면 <b>덮어쓰기</b>다(Ruling 151).
     *
     * <p>덮어쓸 대상을 <b>기존 행에서만</b> 찾는다. 이번 요청이 새로 만든 행을 그 목록에 더하지
     * 않으므로, 한 요청이 같은 칸을 두 번 담으면 두 번째 삽입이 UNIQUE 를 밟고
     * {@code 409 DUPLICATE_WEEKLY_ADDRESS} 가 된다 — 자기모순인 요청을 애플리케이션 선검사가 아니라
     * <b>DB 제약</b>이 막는 형태이며, 선검사는 동시 요청 두 건 사이에서 아무것도 막지 못한다.
     */
    @Transactional
    public List<WeeklyAddress> apply(Long studentId, Long academyId, List<VerifiedAddressEntry> entries) {
        Map<AddressSlot, WeeklyAddress> existing = existingBySlot(studentId, academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            List<WeeklyAddress> applied = entries.stream()
                    .map(entry -> upsert(studentId, academyId, entry, existing, now))
                    .toList();
            weeklyAddressRepository.flush();
            return applied;
        } catch (DataIntegrityViolationException e) {
            throw duplicateSlot(e) ? new BusinessException(ErrorCode.DUPLICATE_WEEKLY_ADDRESS) : e;
        }
    }

    private WeeklyAddress upsert(Long studentId, Long academyId, VerifiedAddressEntry entry,
            Map<AddressSlot, WeeklyAddress> existing, OffsetDateTime now) {
        Long stopId = stopMatcher.matchOrCreate(academyId, entry.point()).getId();
        WeeklyAddress found = existing.get(entry.slot());
        if (found != null) {
            found.reviseTo(entry, stopId, now);
            return found;
        }
        return weeklyAddressRepository.save(WeeklyAddress.verified(studentId, entry, stopId, now));
    }

    private Map<AddressSlot, WeeklyAddress> existingBySlot(Long studentId, Long academyId) {
        Map<AddressSlot, WeeklyAddress> bySlot = new HashMap<>();
        for (WeeklyAddress address : weeklyAddressRepository.findAllByStudentIdAndAcademyId(studentId, academyId)) {
            bySlot.put(new AddressSlot(address.getWeekday(), address.getDirection()), address);
        }
        return bySlot;
    }

    /**
     * 제약 위반이 이 제약의 것인지 가린다 — 다른 제약까지 {@code 409} 로 옮기면 원인이 감춰진다.
     *
     * <p>{@code flush()} 를 메서드 안에서 부르는 이유도 같다. 커밋 시점까지 미루면 예외가 이 메서드
     * 밖에서 터져 번역할 자리를 지나친 뒤가 되고, 사용자에게 {@code 500} 이 나간다.
     */
    private static boolean duplicateSlot(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && WEEKDAY_DIRECTION_UNIQUE_CONSTRAINT.equals(violation.getConstraintName());
    }
}
