package com.seckill.config;

import com.seckill.dto.SeckillResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public SeckillResponse handleException(Exception e) {
        log.error("系统异常", e);
        return SeckillResponse.fail("系统繁忙，请稍后再试");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public SeckillResponse handleIllegalArgument(IllegalArgumentException e) {
        return SeckillResponse.fail(e.getMessage());
    }
}