package org.zeveon.cashflows.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the SPA from classpath:/static (built by frontend-maven-plugin) and
 * forwards unknown non-API paths to index.html so react-router deep links work.
 *
 * @author Stanislav Vafin
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    /** PWA manifest must not be served as octet-stream. */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> mimeMappingCustomizer() {
        return factory -> {
            var mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        var requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // /api/** is handled by controllers before resource resolution
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
