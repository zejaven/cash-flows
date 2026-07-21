package org.zeveon.cashflows.fx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Response of open.er-api.com {@code /v6/latest/{base}}.
 *
 * @author Stanislav Vafin
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeRateResponse {

    private String result;

    @JsonProperty("base_code")
    private String baseCode;

    @JsonProperty("time_last_update_unix")
    private long timeLastUpdateUnix;

    private Map<String, Double> rates;

    public boolean isSuccess() {
        return "success".equals(result);
    }
}
