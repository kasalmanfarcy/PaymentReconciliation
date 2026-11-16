package com.reconflow.paymentreconflow.security.config;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The port the management server actually listens on (M9.10), learnt when it starts: the
 * configured value may be 0 (a random port, in tests). -1 until then, and forever when actuator
 * shares the main port, so nothing is treated as "the management port" by accident.
 */
@Component
class ManagementPort implements ApplicationListener<WebServerInitializedEvent> {

    private final AtomicInteger port = new AtomicInteger(-1);

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if ("management".equals(event.getApplicationContext().getServerNamespace())) {
            port.set(event.getWebServer().getPort());
        }
    }

    boolean is(int localPort) {
        return localPort == port.get();
    }
}
