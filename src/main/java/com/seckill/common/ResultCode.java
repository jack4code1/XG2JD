package com.seckill.common;

/** Standard business result codes returned by the public API. */
public enum ResultCode {
    SUCCESS(0, "成功"),
    BAD_REQUEST(40001, "请求参数错误"),
    OUT_OF_STOCK(40002, "库存不足"),
    DUPLICATE_OPERATION(40003, "请勿重复操作"),
    UNAUTHORIZED(40101, "登录状态已失效，请重新登录"),
    FORBIDDEN(40301, "无权限访问"),
    NOT_FOUND(40401, "资源不存在"),
    SYSTEM_BUSY(50001, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
