package za.co.sibusiso.fraud.evaluation.application;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision;
import za.co.sibusiso.fraud.evaluation.FlowFailure;

import static org.assertj.core.api.Assertions.*;

class EvaluationCursorTest {
    private final StrictYaml yaml=new StrictYaml();
    private final String source=yaml.readText(new ClassPathResource("config/service.example.yaml"));
    private final ServiceLimits limits=yaml.read(source,ServiceLimits.class);
    private final EvaluationCursor codec=new EvaluationCursor("a".repeat(64),limits);
    private final Instant from=Instant.parse("2026-10-15T00:00:00Z"),to=from.plusSeconds(86400);
    private final EvaluationQuery.Request request=new EvaluationQuery.Request("customer",Decision.FLAGGED,from,to,2,null);
    private final EvaluationQuery.Position position=new EvaluationQuery.Position(from.plusSeconds(1),UUID.fromString("11111111-1111-4111-8111-111111111111"));
    @Test void cursorRoundTripsAcrossInstancesAndBindsScopeEveryFilterAndPageSize(){
        String binding=codec.binding("scope",request,2);
        String token=codec.next(binding,new EvaluationQuery.Window("customer",Decision.FLAGGED,from,to,2,null),position);
        var other=new EvaluationCursor("a".repeat(64),limits);var state=other.decode(token,binding);
        assertThat(state.after()).isEqualTo(position.acceptedAt().toString());assertThat(state.id()).isEqualTo(position.id().toString());
        assertThat(state.from()).isEqualTo(from.toString());assertThat(state.to()).isEqualTo(to.toString());
        for(String changed:new String[]{codec.binding("other-scope",request,2),codec.binding("scope",request,3),
                codec.binding("scope",new EvaluationQuery.Request("other",Decision.FLAGGED,from,to,2,null),2),
                codec.binding("scope",new EvaluationQuery.Request("customer",Decision.CLEAR,from,to,2,null),2),
                codec.binding("scope",new EvaluationQuery.Request("customer",Decision.FLAGGED,null,to,2,null),2),
                codec.binding("scope",new EvaluationQuery.Request("customer",Decision.FLAGGED,from,to.plusSeconds(1),2,null),2)})
            assertThatThrownBy(()->other.decode(token,changed)).isInstanceOf(FlowFailure.class).hasMessage("INVALID_CURSOR");
    }
    @Test void unsignedTamperedNoncanonicalOversizedAndRotatedKeyTokensAreRejected(){
        String binding=codec.binding("scope",request,2);
        String token=codec.next(binding,new EvaluationQuery.Window(null,null,from,to,2,null),position);
        for(String malformed:new String[]{"",token.substring(1),"!"+token,token+"=",token+"x",token.replaceFirst(".",token.startsWith("a")?"b":"a"),"a".repeat(2049)})
            assertThatThrownBy(()->codec.decode(malformed,binding)).isInstanceOf(FlowFailure.class);
        assertThatThrownBy(()->new EvaluationCursor("b".repeat(64),limits).decode(token,binding)).isInstanceOf(FlowFailure.class);
    }
    @Test void keyAndConfiguredCursorCapacityFailClosedAtStartup(){
        for(String key:new String[]{"","a","g".repeat(64),"a".repeat(63)})
            assertThatThrownBy(()->new EvaluationCursor(key,limits)).isInstanceOf(IllegalArgumentException.class);
        var shortLimit=yaml.read(source.replace("maxCursorLength: 2048","maxCursorLength: 100"),ServiceLimits.class);
        assertThatThrownBy(()->new EvaluationCursor("a".repeat(64),shortLimit)).hasMessageContaining("cursor length");
    }
}
