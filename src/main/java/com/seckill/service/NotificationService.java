package com.seckill.service;
import com.seckill.model.UserNotification;
import com.seckill.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
@Service @RequiredArgsConstructor
public class NotificationService {
    private final UserNotificationRepository repository;
    public void notify(Long userId, String type, String title, String content) {
        if (userId != null) repository.save(UserNotification.builder().recipientId(userId).type(type).title(title).content(content).build());
    }
    public List<UserNotification> list(Long userId) { return repository.findTop20ByRecipientIdOrderByCreatedAtDesc(userId); }
    public long unread(Long userId) { return repository.countByRecipientIdAndReadAtIsNull(userId); }
    public void read(Long userId, Long id) { repository.findById(id).filter(n -> userId.equals(n.getRecipientId())).ifPresent(n -> { n.setReadAt(LocalDateTime.now()); repository.save(n); }); }
    public int markAllRead(Long userId) { return repository.markAllRead(userId); }
}
