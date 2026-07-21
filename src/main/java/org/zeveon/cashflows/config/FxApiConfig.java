package org.zeveon.cashflows.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zeveon.cashflows.fx.ExchangeRateApi;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * @author Stanislav Vafin
 */
@Configuration
public class FxApiConfig {

    @Bean
    public ExchangeRateApi exchangeRateApi(@Value("${fx.base-url}") String baseUrl) {
        // Deliberately not the Boot-managed ObjectMapper: lenient settings for a third-party API
        var objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(JacksonConverterFactory.create(objectMapper))
                .build()
                .create(ExchangeRateApi.class);
    }
}
