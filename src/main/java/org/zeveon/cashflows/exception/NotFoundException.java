package org.zeveon.cashflows.exception;

/**
 * @author Stanislav Vafin
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Long id) {
        return new NotFoundException("%s %d not found".formatted(entity, id));
    }
}
