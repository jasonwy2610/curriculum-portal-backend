package vn.edu.huflit.portal.academic.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.User;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Admin-only student directory. Returns public academic identity fields, never password hashes. */
@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
public class AdminStudentLookupController {
    private final MongoTemplate mongoTemplate;

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> count() {
        long total = mongoTemplate.count(Query.query(Criteria.where("role").is("STUDENT")), User.class);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("total", total)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        String term = q == null ? "" : q.trim();
        if (term.length() > 100) throw new IllegalArgumentException("Từ khóa tối đa 100 ký tự");
        Criteria criteria = Criteria.where("role").is("STUDENT");
        if (!term.isEmpty()) {
            String regex = Pattern.quote(term);
            criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(
                    Criteria.where("studentCode").regex(regex, "i"),
                    Criteria.where("fullName").regex(regex, "i"),
                    Criteria.where("email").regex(regex, "i")
            ));
        }
        long total = mongoTemplate.count(Query.query(criteria), User.class);
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Order.asc("studentCode"), Sort.Order.asc("_id")))
                .skip((long) safePage * safeSize).limit(safeSize);
        List<Map<String, String>> items = mongoTemplate.find(query, User.class).stream().map(u -> Map.of(
                "id", u.getId().toHexString(),
                "studentCode", u.getStudentCode() == null ? "" : u.getStudentCode(),
                "fullName", u.getFullName() == null ? "" : u.getFullName(),
                "email", u.getEmail() == null ? "" : u.getEmail(),
                "cohort", u.getCohort() == null ? "" : u.getCohort()
        )).toList();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("items", items, "total", total, "page", safePage, "size", safeSize)));
    }
}
