package vn.edu.huflit.portal.major.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.major.dto.CreateMajorRequest;
import vn.edu.huflit.portal.major.dto.UpdateMajorRequest;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.major.dto.MajorResponse;
import vn.edu.huflit.portal.major.service.MajorService;

import java.util.List;

@RestController
@RequestMapping({"/api/majors", "/api/v1/admin/majors"})
@RequiredArgsConstructor
public class MajorController {

    private final MajorService majorService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MajorResponse>>> getAllMajors(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        List<MajorResponse> majors = majorService.getAllMajors(status, keyword);
        return ResponseEntity.ok(ApiResponse.ok(majors));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MajorResponse>> getMajorById(@PathVariable String id) {
        MajorResponse major = majorService.getMajorById(id);
        return ResponseEntity.ok(ApiResponse.ok(major));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<MajorResponse>> getMajorByCode(@PathVariable String code) {
        MajorResponse major = majorService.getMajorByCode(code);
        return ResponseEntity.ok(ApiResponse.ok(major));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MajorResponse>> createMajor(@Valid @RequestBody CreateMajorRequest request) {
        MajorResponse created = majorService.createMajor(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Tạo ngành đào tạo thành công", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MajorResponse>> updateMajor(
            @PathVariable String id,
            @Valid @RequestBody UpdateMajorRequest request
    ) {
        MajorResponse updated = majorService.updateMajor(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật ngành đào tạo thành công", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MajorResponse>> deleteMajor(@PathVariable String id) {
        MajorResponse deleted = majorService.deleteMajor(id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa mềm ngành đào tạo thành công", deleted));
    }
}
