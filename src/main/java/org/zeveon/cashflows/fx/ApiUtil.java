package org.zeveon.cashflows.fx;

import retrofit2.Call;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * @author Stanislav Vafin
 */
public final class ApiUtil {

    private ApiUtil() {
    }

    public static <T> T executeApiCall(Call<T> call) {
        try {
            var response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
            throw new RuntimeException("API call failed or returned null (%d)".formatted(response.code()));
        } catch (SocketTimeoutException e) {
            return executeApiCall(call.clone());
        } catch (IOException e) {
            throw new RuntimeException("IOException during API call", e);
        }
    }
}
