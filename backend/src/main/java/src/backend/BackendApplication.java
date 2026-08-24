package src.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 애플리케이션 진입점. {@code @EnableScheduling} 은 시간 기반 배치(회차 확정 등, Phase 1+)가 쓸 인프라다. */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
