package vn.edu.huflit.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.Notification;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.service.NotificationService;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;

    @GetMapping("/api/v1/student/notifications")
    public ResponseEntity<ApiResponse<List<Map<String,Object>>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(service.listForStudent(user)));
    }
    @GetMapping("/api/v1/student/notifications/{id}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> detail(@AuthenticationPrincipal User user, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.detailForStudent(user, id)));
    }
    @PatchMapping("/api/v1/student/notifications/{id}/read")
    public ResponseEntity<ApiResponse<Map<String,Object>>> markRead(@AuthenticationPrincipal User user, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Đã đánh dấu thông báo đã đọc", service.markRead(user, id)));
    }
    @GetMapping("/api/v1/admin/notifications")
    public ResponseEntity<ApiResponse<List<Notification>>> adminList() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }
    @PostMapping("/api/v1/admin/notifications")
    public ResponseEntity<ApiResponse<Notification>> create(@AuthenticationPrincipal User user, @RequestBody Notification n) {
        return ResponseEntity.ok(ApiResponse.ok("Tạo thông báo thành công", service.create(user, n)));
    }
}
