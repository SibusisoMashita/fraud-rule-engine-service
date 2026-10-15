package za.co.sibusiso.fraud.ingress;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.evaluation.TransactionService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngressFlowBoundaryTest {
    @Test void responseWriteFailureAfterFinancialWorkCannotBeReportedAsInvalidUnacceptedInput() throws Exception {
        var advice=new IngressAdvice();var request=new MockHttpServletRequest();var before=new MockHttpServletResponse();
        advice.unreadable(request,before);assertThat(before.getStatus()).isEqualTo(400);assertThat(before.getContentAsString()).contains("NOT_ACCEPTED");
        request.setAttribute(IngressController.FINANCIAL_RESPONSE,true);var after=new MockHttpServletResponse();
        advice.unreadable(request,after);assertThat(after.getStatus()).isEqualTo(503);assertThat(after.getContentAsString()).contains("UNKNOWN").doesNotContain("NOT_ACCEPTED");
    }
    @Test void tightenedIdentifierConfigurationAppliesToReadsBeforeAnyLookup() {
        var yaml=new StrictYaml();var limits=yaml.read(yaml.readText(new ClassPathResource("config/service.example.yaml"))
                .replace("maxIdentifierLength: 128","maxIdentifierLength: 3"),ServiceLimits.class);
        var service=mock(TransactionService.class);var controller=new IngressController(limits,service);
        var caller=new Caller("producer","scope",Set.of(Caller.Role.READ),Set.of(),1);
        assertThatThrownBy(()->controller.get("four",caller,new MockHttpServletRequest())).isInstanceOf(ApiFailure.class);
        verifyNoInteractions(service);
    }
}
