package src.backend.student.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.dto.WeeklyAddressEntryRequest;
import src.backend.student.dto.WeeklyAddressUpdateRequest;
import src.backend.student.geocoding.spec.GeocodingClient;

/**
 * 요일별 주소 한 번 저장이 공급자를 부르는 횟수 — 같은 주소는 요청 안에서 한 번만, 이미 저장된 그대로인 칸은 부르지 않는다.
 *
 * <p>칸이 최대 14개라 같은 주소를 칸마다 따로 물으면 요청 하나가 공급자 호출 14번이 되고, 공급자 응답 시간이 칸 수만큼 곱해져
 * 요청이 길어진다. 호출은 결정론적 스텁에 스파이를 걸어 센다 — 실제 네이버를 부르지 않는다.
 */
@SpringBootTest
@Transactional
class WeeklyAddressGeocodeCountTest {

    private static final String[] WEEKDAYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    private static final String[] DIRECTIONS = {"to_academy", "from_academy"};

    /** 학원 B 보호자(parentB1)와 그 자녀(studentB1) — 시드에 요일별 주소가 없어 신규 저장 경로를 밟는다. */
    private final AuthUser parent = new AuthUser(9L, 2L, Role.PARENT, AccountStatus.ACTIVE, false);

    private static final long STUDENT_ID = 6L;

    @Autowired
    private WeeklyAddressCommandService commandService;

    @MockitoSpyBean
    private GeocodingClient geocodingClient;

    @Test
    void 열네_칸이_같은_주소면_공급자를_한_번만_부른다() {
        commandService.replace(parent, STUDENT_ID, 열네_칸("서울시 테스트로 100"));

        verify(geocodingClient, times(1)).geocode(any());
    }

    @Test
    void 이미_저장된_그대로의_칸만_다시_보내면_공급자를_부르지_않는다() {
        commandService.replace(parent, STUDENT_ID, 열네_칸("서울시 테스트로 100"));
        clearInvocations(geocodingClient);

        commandService.replace(parent, STUDENT_ID, 열네_칸("서울시 테스트로 100"));

        verify(geocodingClient, never()).geocode(any());
    }

    @Test
    void 한_칸만_바꾸면_바뀐_주소만_부른다() {
        commandService.replace(parent, STUDENT_ID, 열네_칸("서울시 테스트로 100"));
        clearInvocations(geocodingClient);
        List<WeeklyAddressEntryRequest> entries = new ArrayList<>(열네_칸("서울시 테스트로 100").entries());
        entries.set(0, 칸(WEEKDAYS[0], DIRECTIONS[0], "서울시 테스트로 120"));

        commandService.replace(parent, STUDENT_ID, new WeeklyAddressUpdateRequest(entries));

        verify(geocodingClient, times(1)).geocode(any());
        verify(geocodingClient, times(1)).geocode("서울시 테스트로 120");
    }

    private static WeeklyAddressUpdateRequest 열네_칸(String address) {
        List<WeeklyAddressEntryRequest> entries = new ArrayList<>();
        for (String weekday : WEEKDAYS) {
            for (String direction : DIRECTIONS) {
                entries.add(칸(weekday, direction, address));
            }
        }
        return new WeeklyAddressUpdateRequest(entries);
    }

    private static WeeklyAddressEntryRequest 칸(String weekday, String direction, String address) {
        return new WeeklyAddressEntryRequest(weekday, direction, address, null);
    }
}
