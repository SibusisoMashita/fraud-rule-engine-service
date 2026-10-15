package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IngressController.class)
@Profile("!migrate & !policy-admin")
public final class IngressAdvice {
    @ExceptionHandler(ApiFailure.class)
    public void rejected(ApiFailure failure, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiProblems.write(request, response, failure);
    }
    @ExceptionHandler(IOException.class)
    public void unreadable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiProblems.write(request, response, new ApiFailure(400, "UNREADABLE_BODY"));
    }
}
