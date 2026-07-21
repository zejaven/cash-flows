package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.ConversionDtos.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface ConversionRouteService {

    List<RouteDto> findAll();

    RouteDto create(RouteRequest request);

    RouteDto update(Long id, RouteRequest request);

    void delete(Long id);

    CalcResult calculate(Long id, CalcRequest request);

    /** Non-archived routes converting {@code from} -> {@code to}, best first (FR-5.4). */
    List<CompareRow> compare(String from, String to, BigDecimal amount);
}
