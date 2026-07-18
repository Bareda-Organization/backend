package src.backend.location.websocket;

import java.security.Principal;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import src.backend.global.security.AuthUser;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * STOMP 세션 연결/해제를 {@link LocationSessionRegistry}에 반영한다.
 * Principal(AuthUser)은 {@code StompAuthChannelInterceptor}가 CONNECT 시점에 세션에 심어둔 것이라
 * 여기서도 그대로 전달돼 온다(재조회 없이 세션에서 복원).
 */
@Component
public class LocationSocketEventListener {

    private final LocationSessionRegistry registry;
    private final StudentRepository studentRepository;

    public LocationSocketEventListener(LocationSessionRegistry registry, StudentRepository studentRepository) {
        this.registry = registry;
        this.studentRepository = studentRepository;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        resolveStudentId(event.getUser()).ifPresent(registry::markConnected);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        resolveStudentId(event.getUser()).ifPresent(registry::markDisconnected);
    }

    private Optional<Long> resolveStudentId(Principal principal) {
        if (!(principal instanceof AuthUser authUser)) {
            return Optional.empty();
        }
        return studentRepository.findByUserId(authUser.userId()).map(Student::getId);
    }
}
