package com.travelguide.service.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key="
})
class IdempotentOperationServiceTest {
    @Autowired
    private IdempotentOperationService service;

    @Test
    void executesBusinessWriteOncePerOperationKey() {
        AtomicInteger calls = new AtomicInteger();

        String first = service.execute("test:write:1", String.class, () -> {
            calls.incrementAndGet();
            return "result";
        });
        String replay = service.execute("test:write:1", String.class, () -> {
            calls.incrementAndGet();
            return "duplicate";
        });

        assertThat(first).isEqualTo("result");
        assertThat(replay).isEqualTo("result");
        assertThat(calls).hasValue(1);
    }
}
