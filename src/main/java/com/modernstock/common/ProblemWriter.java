package com.modernstock.common;

import java.io.IOException;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Builds the one error format (RFC 7807 ProblemDetail + "code") with a translated message. */
@Component
@RequiredArgsConstructor
public class ProblemWriter {

    public static final Locale KHMER = Locale.forLanguageTag("km");

    private final MessageSource messages;
    private final ObjectMapper json;

    public ProblemDetail problem(HttpStatus status, String code, Object[] args, Locale locale) {
        String detail = messages.getMessage(code, args, code, locale);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }

    public ProblemDetail problem(ApiException e, Locale locale) {
        return problem(e.getStatus(), e.getCode(), e.getArgs(), locale);
    }

    /** For filters, which run before Spring MVC's exception handling. */
    public void write(HttpServletRequest request, HttpServletResponse response, ApiException e) throws IOException {
        write(request, response, e.getStatus(), e.getCode());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        ProblemDetail problem = problem(status, code, null, localeOf(request));
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem);
    }

    public static Locale localeOf(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        return header != null && header.toLowerCase().startsWith("km") ? KHMER : Locale.ENGLISH;
    }
}
