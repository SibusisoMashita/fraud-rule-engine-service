package za.co.sibusiso.fraud.ingress;

import java.util.HexFormat;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import za.co.sibusiso.fraud.configuration.ServiceLimits;

@Component
@Profile("!migrate & !policy-admin")
public final class SharedRateLimiter {
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = script();
    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> script() {
        var script = new DefaultRedisScript<List>();
        script.setLocation(new ClassPathResource("redis/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
    private final StringRedisTemplate redis;
    private final ServiceLimits limits;
    public SharedRateLimiter(StringRedisTemplate redis, ServiceLimits limits) { this.redis = redis; this.limits = limits; }

    public void acquire(Caller caller, IngressRoute route) {
        var bucket = route.bucket(limits);
        List<?> result;
        try { result = redis.execute(SCRIPT, List.of(key(caller, route)),
                Integer.toString(bucket.capacity()), Integer.toString(bucket.refillPerSecond()));
        } catch (RuntimeException unavailable) { throw new ApiFailure(503, "LIMITER_UNAVAILABLE", 1); }
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long allowed)
                || !(result.get(1) instanceof Long retry) || (allowed != 0 && allowed != 1) || retry < 0) {
            throw new ApiFailure(503, "LIMITER_UNAVAILABLE", 1);
        }
        if (allowed == 0) throw new ApiFailure(429, "RATE_LIMITED", Math.max(1, (retry + 999) / 1000));
    }

    public static String key(Caller caller, IngressRoute route) {
        return "fraud:quota:" + HexFormat.of().formatHex(CredentialAuthenticator.digest(caller.principalId())) + ":" + route.name();
    }
}
