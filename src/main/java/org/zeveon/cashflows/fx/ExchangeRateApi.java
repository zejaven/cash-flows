package org.zeveon.cashflows.fx;

import org.zeveon.cashflows.fx.model.ExchangeRateResponse;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

/**
 * @author Stanislav Vafin
 */
public interface ExchangeRateApi {

    @GET("/v6/latest/{base}")
    Call<ExchangeRateResponse> getLatest(@Path("base") String base);
}
