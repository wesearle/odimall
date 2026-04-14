package com.odimall.gateway.filter;

import com.odimall.gateway.processor.OdiMallRequestProcessor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomHeaderFilter implements Filter {

    private final OdiMallRequestProcessor requestProcessor;

    public CustomHeaderFilter(OdiMallRequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = UUID.randomUUID().toString();

        String correlationId = httpRequest.getHeader("X-OdiMall-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        Map<String, String> customHeaders = requestProcessor.buildCustomHeaders(requestId, correlationId);
        customHeaders.forEach(httpResponse::setHeader);

        String endpoint = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        requestProcessor.processRequest(requestId, endpoint, method);

        request.setAttribute("X-OdiMall-Request-Id", requestId);
        request.setAttribute("X-OdiMall-Correlation-Id", correlationId);
        request.setAttribute("X-OdiMall-Timestamp", customHeaders.get("X-OdiMall-Timestamp"));

        chain.doFilter(request, response);
    }
}
