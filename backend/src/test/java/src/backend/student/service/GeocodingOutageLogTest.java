package src.backend.student.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.student.dto.WeeklyAddressEntryRequest;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;
import src.backend.student.query.StopSearchQueryService;
import src.backend.student.repository.StopRepository;
import src.backend.global.security.AuthUser;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 공급자 장애가 503 으로 번역될 때 운영자가 원인을 알 수 있게 WARN 한 줄이 남고, 그 줄에는 주소·검색어가 실리지 않는다(L3).
 * 예외 객체를 그대로 로그에 넘기면 스택과 원인 메시지가 같이 나가므로 이벤트에 예외가 붙지 않아야 한다.
 */
class GeocodingOutageLogTest {

    private static final String SECRET = "서울시 비밀로 77";

    private final GeocodingClient geocodingClient = mock(GeocodingClient.class);

    private final PlaceSearchClient placeSearchClient = mock(PlaceSearchClient.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger verificationLogger = (Logger) LoggerFactory.getLogger(AddressVerification.class);

    private final Logger searchLogger = (Logger) LoggerFactory.getLogger(StopSearchQueryService.class);

    @BeforeEach
    void 로그를_모은다() {
        appender.start();
        verificationLogger.addAppender(appender);
        searchLogger.addAppender(appender);
    }

    @AfterEach
    void 로그_수집을_끝낸다() {
        verificationLogger.detachAppender(appender);
        searchLogger.detachAppender(appender);
    }

    @Test
    void 공급자_장애는_503_이고_원인_클래스가_든_WARN_한_줄이_남는다() {
        given(geocodingClient.geocode(anyString())).willThrow(
                new GeocodingUnavailableException("지오코딩 호출 실패", new IllegalStateException("GET ?query=" + SECRET)));

        BusinessException thrown = catchThrowableOfType(BusinessException.class,
                () -> new AddressVerification(geocodingClient).verifyAll(
                        List.of(new WeeklyAddressEntryRequest("mon", "to_academy", SECRET, null))));

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ADDRESS_VERIFICATION_UNAVAILABLE);
        List<ILoggingEvent> warns = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertThat(warns).as("WARN 정확히 한 줄").hasSize(1);
        assertThat(warns.getFirst().getFormattedMessage()).contains("GeocodingUnavailableException")
                .contains("IllegalStateException").doesNotContain("비밀로");
        assertThat(warns.getFirst().getThrowableProxy()).as("예외 객체를 붙이면 원인 메시지가 같이 나간다").isNull();
    }

    @Test
    void 한_칸_검증도_같은_WARN_을_남긴다() {
        given(geocodingClient.geocode(anyString())).willThrow(
                new GeocodingUnavailableException("지오코딩 호출 실패", new IllegalStateException("GET ?query=" + SECRET)));

        catchThrowableOfType(BusinessException.class, () -> new AddressVerification(geocodingClient).verifySingle(SECRET));

        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("비밀로"));
    }

    @Test
    void 장소_검색_실패_WARN_에는_검색어도_예외_객체도_없다() {
        given(placeSearchClient.search(anyString())).willThrow(
                new GeocodingUnavailableException("장소 검색 호출 실패", new IllegalStateException("GET ?query=" + SECRET)));
        given(geocodingClient.candidates(anyString())).willReturn(List.of());
        StopSearchQueryService service = new StopSearchQueryService(mock(StopRepository.class), geocodingClient,
                placeSearchClient);

        service.suggest(new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE, false), SECRET);

        List<ILoggingEvent> warns = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst().getFormattedMessage()).doesNotContain("비밀로");
        assertThat(warns.getFirst().getThrowableProxy()).isNull();
    }
}
