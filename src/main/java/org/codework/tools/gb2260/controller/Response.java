package org.codework.tools.gb2260.controller;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Optional;

import static org.springframework.http.HttpStatus.*;

/**
 * @author benjaminkc
 * 接口回复
 */
public class Response extends HashMap {

    public static Response resp(HttpStatus status, String message) {
        if (StringUtils.isEmpty(message))
            message = status.getReasonPhrase();
        return build(status, message);
    }

    public static Response resp(HttpStatus status) {
        return resp(status, status.getReasonPhrase());
    }

    public static Response ok(String key, Object value) {
        if (!StringUtils.isEmpty(key) && Optional.ofNullable(value).isPresent())
            return ok().add(key, value);
        else
            return ok();
    }

    public static Response ok() {
        return resp(OK);
    }

    public static Response badRequest(String message) {
        return resp(BAD_REQUEST, message);
    }

    public static Response error(String message) {
        return resp(INTERNAL_SERVER_ERROR, message);
    }

    public static Response error() {
        return resp(INTERNAL_SERVER_ERROR);
    }

    private static Response build(HttpStatus status, String message) {
        Response r = new Response();
        r.put("statusCode", status.value());
        r.put("message", message);
        return r;
    }

    public Response add(String key, Object value) {
        this.put(key, value);
        return this;
    }

}
