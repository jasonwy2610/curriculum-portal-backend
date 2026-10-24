package vn.edu.huflit.portal.replacement.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.service.ReplacementRuleService;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ReplacementRuleController {
    private final ReplacementRuleService service;

    @GetMapping("/api/v1/student/replacement-rules")
    public ResponseEntity<ApiResponse<List<ReplacementRule>>> myRules(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(service.findApplicable(user)));
    }

    @GetMapping("/api/v1/student/replacement-rules/{id}")
    public ResponseEntity<ApiResponse<ReplacementRule>> myRule(@AuthenticationPrincipal User user, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getApplicable(user, id)));
    }

    @GetMapping("/api/v1/admin/replacement-rules")
    public ResponseEntity<ApiResponse<List<ReplacementRule>>> allRules() {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll()));
    }

    @PostMapping("/api/v1/admin/replacement-rules")
    public ResponseEntity<ApiResponse<ReplacementRule>> create(@AuthenticationPrincipal User user, @RequestBody ReplacementRule rule) {
        return ResponseEntity.ok(ApiResponse.ok("Tạo quy tắc thay thế thành công", service.create(user, rule)));
    }

    @PutMapping("/api/v1/admin/replacement-rules/{id}")
    public ResponseEntity<ApiResponse<ReplacementRule>> update(@AuthenticationPrincipal User user, @PathVariable String id, @RequestBody ReplacementRule rule) {
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật quy tắc thay thế thành công", service.update(user, id, rule)));
    }
}
