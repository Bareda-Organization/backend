package testsupport.redis;

/** Redis 장애 두 가지 — 프로세스가 사라진 경우({@link #STOPPED})와 연결은 살아 있는데 답이 없는 경우({@link #PAUSED}). */
public enum Outage {

    STOPPED {
        @Override
        public void begin(OutageRedis redis) {
            redis.stop();
        }

        @Override
        public void end(OutageRedis redis) {
            redis.restart();
        }
    },

    PAUSED {
        @Override
        public void begin(OutageRedis redis) {
            redis.pause();
        }

        @Override
        public void end(OutageRedis redis) {
            redis.unpause();
        }
    };

    public abstract void begin(OutageRedis redis);

    public abstract void end(OutageRedis redis);
}
