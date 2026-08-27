package com.seckill.controller;
import com.seckill.common.Result;
import com.seckill.model.UserNotification;
import com.seckill.service.NotificationService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/api/notifications") @RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;
    @GetMapping public Result<List<UserNotification>> list() { return Result.ok(notificationService.list(UserContext.getUserId())); }
    @GetMapping("/unread") public Result<Map<String, Long>> unread() { return Result.ok(Map.of("count", notificationService.unread(UserContext.getUserId()))); }
    @PostMapping("/{id}/read") public Result<Void> read(@PathVariable Long id) { notificationService.read(UserContext.getUserId(), id); return Result.ok(); }
    @PostMapping("/read-all") public Result<Map<String, Integer>> readAll() { return Result.ok(Map.of("updated", notificationService.markAllRead(UserContext.getUserId()))); }
}
