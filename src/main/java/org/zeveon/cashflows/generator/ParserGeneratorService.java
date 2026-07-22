package org.zeveon.cashflows.generator;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorOptions;
import org.zeveon.cashflows.model.dto.GeneratorDtos.StartResponse;
import org.zeveon.cashflows.model.dto.GeneratorDtos.TaskSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Stanislav Vafin
 */
public interface ParserGeneratorService {

    GeneratorOptions options();

    /**
     * Starts an agentic generation of {@code parsers/<scriptName>.py} from the
     * uploaded sample statements (several samples let the agent see more layout
     * variants; the generated parser must handle every one of them). One run at
     * a time; provider/model come from the persisted generator settings.
     */
    StartResponse start(List<MultipartFile> files, String bankName, String scriptName);

    Optional<TaskSnapshot> current();

    SseEmitter events(UUID taskId);

    void cancel(UUID taskId);
}
