package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** 접속 IP 는 프록시가 적은 {@code X-Real-IP} 를 프록시(루프백·사설 주소)에서 온 요청에서만 믿는다 — 인터넷에서 직접 온 요청의 헤더는 요청자가 적은 값이다. */
class ClientIpTest {

    private static MockHttpServletRequest request(String peer, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.0.7", "::1", "fd12:3456::1", "fe80::1"})
    @DisplayName("루프백·사설 주소의 프록시가 준 X-Real-IP 는 그 값을 쓴다")
    void 프록시가_준_헤더는_쓴다(String proxy) {
        assertThat(ClientIp.of(request(proxy, "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.50", "8.8.8.8", "172.32.0.1", "2001:db8::50"})
    @DisplayName("공인 주소에서 직접 온 요청의 X-Real-IP 는 무시하고 접속 주소를 쓴다")
    void 공인_주소의_헤더는_무시한다(String peer) {
        String peerAddress = java.net.InetAddress.ofLiteral(peer).getHostAddress();

        assertThat(ClientIp.of(request(peer, "198.51.100.1"))).isEqualTo(peerAddress);
    }

    @Test
    @DisplayName("프록시가 준 X-Real-IP 가 IP 표기가 아니면 접속 주소를 쓴다")
    void 해석할_수_없는_헤더는_접속_주소로_대체한다() {
        assertThat(ClientIp.of(request("10.0.0.5", "not-an-ip"))).isEqualTo("10.0.0.5");
        assertThat(ClientIp.of(request("10.0.0.5", "  "))).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("IPv6 X-Real-IP 도 프록시에서 온 것이면 쓴다")
    void IPv6_헤더도_쓴다() {
        assertThat(ClientIp.of(request("::1", "2001:db8::1")))
                .isEqualTo(java.net.InetAddress.ofLiteral("2001:db8::1").getHostAddress());
    }

    @Test
    @DisplayName("접속 주소도 IP 표기가 아니면 null")
    void 접속_주소가_IP가_아니면_null() {
        assertThat(ClientIp.of(request("unknown-host", null))).isNull();
    }
}
