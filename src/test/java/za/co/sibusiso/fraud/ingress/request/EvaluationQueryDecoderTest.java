package za.co.sibusiso.fraud.ingress.request;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

import static org.assertj.core.api.Assertions.*;

class EvaluationQueryDecoderTest {
    private final ServiceLimits limits=new StrictYaml().read(new ClassPathResource("config/service.example.yaml"),ServiceLimits.class);
    private final EvaluationQueryDecoder decoder=new EvaluationQueryDecoder(limits.api());
    @Test void defaultsRemainUnspecifiedUntilDatabaseClockSelectionAndOffsetsNormalize(){
        var defaults=decoder.decode(Map.of());assertThat(defaults.from()).isNull();assertThat(defaults.to()).isNull();assertThat(defaults.pageSize()).isNull();
        var value=decoder.decode(Map.of("customerId",new String[]{"customer"},"decision",new String[]{"FLAGGED"},
                "acceptedFrom",new String[]{"2026-10-16T02:00:00+02:00"},"pageSize",new String[]{"2"}));
        assertThat(value.from().toString()).isEqualTo("2026-10-16T00:00:00Z");assertThat(value.pageSize()).isEqualTo(2);
    }
    static Stream<Map<String,String[]>> malformed(){return Stream.of(
            Map.of("scope",new String[]{"forged"}),Map.of("offset",new String[]{"1"}),Map.of("sort",new String[]{"desc"}),
            Map.of("customerId",new String[]{"a","b"}),Map.of("customerId",new String[]{"x' OR 1=1"}),
            Map.of("customerId",new String[]{"a".repeat(129)}),Map.of("decision",new String[]{"clear"}),
            Map.of("decision",new String[]{""}),Map.of("pageSize",new String[]{"1.5"}),Map.of("pageSize",new String[]{"01"}),
            Map.of("cursor",new String[]{""}),Map.of("cursor",new String[]{"a".repeat(2049)}),
            Map.of("acceptedFrom",new String[]{"2026-10-16T00:00:00"}),Map.of("acceptedTo",new String[]{"2026-02-30T00:00:00Z"}),
            Map.of("acceptedTo",new String[]{"2026-10-16T00:00:00.0000001Z"}),Map.of("acceptedTo",new String[]{"0000-01-01T00:00:00Z"}));}
    @ParameterizedTest @MethodSource("malformed") void rejectsMalformedUnknownDuplicateOrOversizedParameters(Map<String,String[]> parameters){
        assertThatThrownBy(()->decoder.decode(parameters)).isInstanceOf(ApiFailure.class).extracting(f->((ApiFailure)f).status().value()).isEqualTo(400);
    }
    @Test void operationalPageMaximumIsAppliedWithoutChangingTheBusinessPolicy(){
        for(String size:new String[]{"0","-1","101","2147483648","9".repeat(20)})
            assertThatThrownBy(()->decoder.decode(Map.of("pageSize",new String[]{size}))).isInstanceOf(ApiFailure.class)
                    .extracting(f->((ApiFailure)f).status().value()).isEqualTo(422);
    }
}
