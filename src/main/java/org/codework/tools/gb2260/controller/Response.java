package org.codework.tools.gb2260.controller;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Optional;

import static org.springframework.http.HttpStatus.*;

/**
 * 统一 Web API 响应封装类
 * <p>
 * 继承自 {@link HashMap}，提供链式构建标准 JSON 响应结构（包含 statusCode、message、以及动态挂载的数据体）
 *
 * @author benjaminkc
 */
public class Response extends HashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    /**
     * 根据 HTTP 状态码与自定义消息构造响应
     *
     * @param status  HTTP 状态枚举
     * @param message 提示消息
     * @return Response 实例
     */
    public static Response resp(HttpStatus status, String message) {
        if (StringUtils.isEmpty(message)) {
            message = status.getReasonPhrase();
        }
        return build(status, message);
    }

    /**
     * 根据 HTTP 状态码构造默认响应
     *
     * @param status HTTP 状态枚举
     * @return Response 实例
     */
    public static Response resp(HttpStatus status) {
        return resp(status, status.getReasonPhrase());
    }

    /**
     * 构造成功响应（HTTP 200 OK）并附加键值对数据
     *
     * @param key   数据键名
     * @param value 数据值对象
     * @return Response 实例
     */
    public static Response ok(String key, Object value) {
        if (!StringUtils.isEmpty(key) && Optional.ofNullable(value).isPresent()) {
            return ok().add(key, value);
        } else {
            return ok();
        }
    }

    /**
     * 构造默认成功响应（HTTP 200 OK）
     *
     * @return Response 实例
     */
    public static Response ok() {
        return resp(OK);
    }

    /**
     * 构造参数错误响应（HTTP 400 Bad Request）
     *
     * @param message 错误提示信息
     * @return Response 实例
     */
    public static Response badRequest(String message) {
        return resp(BAD_REQUEST, message);
    }

    /**
     * 构造系统内部错误响应（HTTP 500 Internal Server Error）
     *
     * @param message 错误提示信息
     * @return Response 实例
     */
    public static Response error(String message) {
        return resp(INTERNAL_SERVER_ERROR, message);
    }

    /**
     * 构造系统内部错误响应（HTTP 500 Internal Server Error，默认消息）
     *
     * @return Response 实例
     */
    public static Response error() {
        return resp(INTERNAL_SERVER_ERROR);
    }

    /**
     * 内部通用构建方法
     *
     * @param status  HTTP 状态枚举
     * @param message 提示消息
     * @return Response 实例
     */
    private static Response build(HttpStatus status, String message) {
        Response r = new Response();
        r.put("statusCode", status.value());
        r.put("message", message);
        return r;
    }

    /**
     * 链式追加响应属性
     *
     * @param key   属性键名
     * @param value 属性值
     * @return 当前 Response 实例
     */
    public Response add(String key, Object value) {
        this.put(key, value);
        return this;
    }
}
