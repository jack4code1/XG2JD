package com.seckill.config;

import com.seckill.dto.SeckillResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public SeckillResponse handleException(Exception e) {
        log.error("系统异常", e);
        return SeckillResponse.fail("系统繁忙，请稍后再试");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public SeckillResponse handleIllegalArgument(IllegalArgumentException e) {
        return SeckillResponse.fail(e.getMessage());
    }
}