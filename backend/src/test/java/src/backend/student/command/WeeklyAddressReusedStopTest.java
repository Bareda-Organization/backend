package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.dto.WeeklyAddressEntryRequest;
import src.backend.student.dto.WeeklyAddressUpdateRequest;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 이미 저장된 그대로 다시 보낸 칸은 공급자를 부르지 않고 저장된 좌표를 쓴다 — 그 좌표 자리에 승하차지가 없어 새로 만들어야 하면 새 승하차지의 이름과 주소는
 * 사용자가 친 문구가 아니라 그 칸이 가리키던 승하차지의 주소다.
 */
@SpringBootTest
@Transactional
class WeeklyAddressReusedStopTest {

    /** 학원 B 보호자(parentB1)와 그 자녀(studentB1) — 시드에 요일별 주소가 없어 신규 저장 경로를 밟는다. */
    private final AuthUser parent = new AuthUser(9L, 2L, Role.PARENT, AccountStatus.ACTIVE, false);

    private static final long STUDENT_ID = 6L;

    private static final long ACADEMY_ID = 2L;

    @Autowired
    private WeeklyAddressCommandService commandService;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private GeocodingClient geocodingClient;

    @Test
    void 저장된_좌표_자리의_승하차지가_사라졌으면_새_승하차지는_옛_승하차지의_주소를_쓴다() {
        WeeklyAddressUpdateRequest request = new WeeklyAddressUpdateRequest(
                List.of(new WeeklyAddressEntryRequest("mon", "to_academy", "서울시 테스트로 100", null)));
        commandService.replace(parent, STUDENT_ID, request);
        long oldStopId = 칸의_승하차지().getId();
        // 옛 승하차지가 다른 곳으로 옮겨져 저장된 좌표 50m 안에 승하차지가 없다
        entityManager.flush();
        jdbcTemplate.update("UPDATE stop SET address = '서울특별시 강남구 도로명 주소', lat = lat + 0.01 WHERE id = ?",
                oldStopId);
        entityManager.clear();
        clearInvocations(geocodingClient);

        commandService.replace(parent, STUDENT_ID, request);

        verify(geocodingClient, never()).geocode(any());
        Stop recreated = 칸의_승하차지();
        assertThat(recreated.getId()).as("저장된 좌표 자리에 새 승하차지가 만들어졌다").isNotEqualTo(oldStopId);
        assertThat(recreated.getAddress()).isEqualTo("서울특별시 강남구 도로명 주소");
    }

    private Stop 칸의_승하차지() {
        Long stopId = weeklyAddressRepository.findAllByStudentIdAndAcademyId(STUDENT_ID, ACADEMY_ID).get(0).getStopId();
        return stopRepository.findById(stopId).orElseThrow();
    }
}
