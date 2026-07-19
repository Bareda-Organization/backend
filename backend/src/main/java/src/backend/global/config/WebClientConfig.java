package src.backend.global.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.netty.http.client.HttpClient;

/**
 * 이 코드베이스 최초의 외부 HTTP 클라이언트 — routing 모듈이 directions API(OSRM/Naver) 호출에 쓴다.
 * 서블릿(MVC) 앱이라 리액티브 체인 전체를 쓰지 않고 {@code .block()}으로 동기 호출하되,
 * 외부 API 무응답 시 요청 스레드가 무한 대기하지 않도록 connect/read/write 타임아웃을 명시한다.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));

        return builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }
}
