package com.example.demo.Config;

import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebMvcConfigTest {

    @Test
    void shouldPropagateRequestContextIntoAsyncTask() {
        WebMvcConfig config = new WebMvcConfig(new CorsProperties());
        TaskDecorator taskDecorator = config.requestContextTaskDecorator();

        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletRequestAttributes requestAttributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(requestAttributes);

        AtomicReference<RequestAttributes> seenRequestAttributes = new AtomicReference<>();
        AtomicReference<Boolean> saTokenContextValid = new AtomicReference<>(false);
        AtomicReference<Object> saTokenRequest = new AtomicReference<>();
        Runnable decorated = taskDecorator.decorate(() -> {
            seenRequestAttributes.set(RequestContextHolder.getRequestAttributes());
            SaTokenContextForSpringInJakartaServlet saTokenContext =
                    new SaTokenContextForSpringInJakartaServlet();
            saTokenContextValid.set(saTokenContext.isValid());
            saTokenRequest.set(saTokenContext.getRequest().getSource());
        });

        RequestContextHolder.resetRequestAttributes();
        decorated.run();

        assertSame(requestAttributes, seenRequestAttributes.get());
        assertTrue(saTokenContextValid.get());
        assertSame(request, saTokenRequest.get());
        assertNull(RequestContextHolder.getRequestAttributes());
        requestAttributes.requestCompleted();
    }
}
