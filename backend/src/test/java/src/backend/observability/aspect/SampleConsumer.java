package src.backend.observability.aspect;

/**
 * {@link KafkaListenerMetricsAspectTest} 전용 더미 대상. 실제 {@code @KafkaListener} 컨슈머(
 * {@code BusLocationPushConsumer} 등)와 같은 최상위 클래스 형태를 흉내낸다 — 테스트 클래스의 중첩
 * static 클래스로 두면 CGLIB 프록시 생성 시 바깥 클래스 이름까지 simpleName 에 섞여
 * ({@code "OuterClass$SampleConsumer$$SpringCGLIB$$0"}) 실제 런타임과 다른 문자열이 나온다.
 */
class SampleConsumer {
}
