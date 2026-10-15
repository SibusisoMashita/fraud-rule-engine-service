package za.co.sibusiso.fraud;

import java.io.IOException;
import java.util.Properties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import za.co.sibusiso.fraud.configuration.ServiceLimits;

/** Serve the maintained contract and pinned UI locally; no generated duplicate specification. */
@Configuration
@Profile("!migrate & !policy-admin")
class DocumentationConfiguration implements WebMvcConfigurer {
    private final boolean enabled;
    private final String version;
    DocumentationConfiguration(ServiceLimits limits) throws IOException {
        enabled = limits.api().documentationEnabled();
        var properties = new Properties();
        try (var input = new ClassPathResource("META-INF/maven/org.webjars/swagger-ui/pom.properties").getInputStream()) {
            properties.load(input);
        }
        version = java.util.Objects.requireNonNull(properties.getProperty("version"));
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!enabled) return;
        registry.addResourceHandler("/swagger-ui/assets/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/" + version + "/")
                .setCacheControl(CacheControl.noStore());
        registry.addResourceHandler("/swagger-ui/**").addResourceLocations("classpath:/documentation/")
                .setCacheControl(CacheControl.noStore());
        registry.addResourceHandler("/openapi.yaml").addResourceLocations("classpath:/api/")
                .setCacheControl(CacheControl.noStore());
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        if (!enabled) return;
        for (String path : new String[]{"/swagger-ui", "/swagger-ui/", "/swagger-ui.html"})
            registry.addRedirectViewController(path, "/swagger-ui/index.html");
    }
}
