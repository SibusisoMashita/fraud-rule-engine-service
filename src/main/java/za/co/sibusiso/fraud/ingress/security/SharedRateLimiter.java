package za.co.sibusiso.fraud.ingress.security;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

@Component
@Profile("!migrate & !policy-admin")
public final class SharedRateLimiter {
    private static final long MILLIS_PER_SECOND = TimeUnit.SECONDS.toMillis(1);

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

    public SharedRateLimiter(StringRedisTemplate redis, ServiceLimits limits) {
        this.redis = redis;
        this.limits = limits;
    }

    public void acquire(Caller caller, IngressRoute route) {
        var bucket = route.bucket(limits);
        List<?> result;
        try {
            result =
                    redis.execute(
                            SCRIPT,
                            List.of(key(caller, route)),
                            Integer.toString(bucket.capacity()),
                            Integer.toString(bucket.refillPerSecond()));
        } catch (RuntimeException unavailable) {
            throw new ApiFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.LIMITER_UNAVAILABLE,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS,
                    unavailable);
        }
        if (result == null
                || result.size() != 2
                || !(result.get(0) instanceof Long allowed)
                || !(result.get(1) instanceof Long retry)
                || (allowed != 0 && allowed != 1)
                || retry < 0) {
            throw new ApiFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.LIMITER_UNAVAILABLE,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS);
        }
        if (allowed == 0) {
            long retryAfterSeconds = Math.max(1, Math.ceilDiv(retry, MILLIS_PER_SECOND));
            throw new ApiFailure(
                    HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, retryAfterSeconds);
        }
    }

    public static String key(Caller caller, IngressRoute route) {
        return "fraud:quota:"
                + HexFormat.of().formatHex(CredentialAuthenticator.digest(caller.principalId()))
                + ":"
                + route.name();
    }
}
