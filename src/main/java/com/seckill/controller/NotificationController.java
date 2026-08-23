package com.seckill.controller;
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
    @GetMapping public List<UserNotification> list() { return notificationService.list(UserContext.getUserId()); }
    @GetMapping("/unread") public Map<String, Long> unread() { return Map.of("count", notificationService.unread(UserContext.getUserId())); }
    @PostMapping("/{id}/read") public Map<String, Boolean> read(@PathVariable Long id) { notificationService.read(UserContext.getUserId(), id); return Map.of("success", true); }
    @PostMapping("/read-all") public Map<String, Integer> readAll() { return Map.of("updated", notificationService.markAllRead(UserContext.getUserId())); }
}
