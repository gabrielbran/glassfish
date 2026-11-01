package org.atlas.traffic.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Server-wide filter, declared once in the domain's default-web.xml, so every web application gets it
 * without any change to its code. It counts the requests in flight and applies the drain mode:
 * <ul>
 * <li>{@code SERVE}: pass through;</li>
 * <li>{@code SERVE_AND_CLOSE}: serve the request with {@code Connection: close}, so keep-alive clients
 * (and the load balancer) open their next connection elsewhere;</li>
 * <li>{@code REJECT}: {@code 503} with {@code Retry-After} and {@code Connection: close}.</li>
 * </ul>
 * Limitation: asynchronous servlets are counted only until their initial dispatch returns.
 */
public class DrainFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        TrafficGate gate = TrafficGate.current();
        if (gate == null || !(request instanceof HttpServletRequest httpRequest)
            || gate.excluded(httpRequest.getLocalPort(), httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        switch (gate.mode()) {
            case REJECT -> {
                httpResponse.setHeader("Connection", "close");
                httpResponse.setHeader("Retry-After", "5");
                httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Instance draining");
                return;
            }
            case SERVE_AND_CLOSE -> httpResponse.setHeader("Connection", "close");
            case SERVE -> {
                // normal operation
            }
        }
        gate.enter();
        try {
            chain.doFilter(request, response);
        } finally {
            gate.exit();
        }
    }
}
