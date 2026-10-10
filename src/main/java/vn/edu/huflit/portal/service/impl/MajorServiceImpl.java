package vn.edu.huflit.portal.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.dto.request.CreateMajorRequest;
import vn.edu.huflit.portal.dto.request.UpdateMajorRequest;
import vn.edu.huflit.portal.dto.response.MajorResponse;
import vn.edu.huflit.portal.entity.Major;
import vn.edu.huflit.portal.repository.MajorRepository;
import vn.edu.huflit.portal.service.MajorService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MajorServiceImpl implements MajorService {

    private final MajorRepository majorRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public List<MajorResponse> getAllMajors(String status, String keyword) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (status != null && !status.trim().isEmpty()) {
            criteriaList.add(Criteria.where("status").is(status.trim().toUpperCase()));
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            String sanitizedKeyword = Pattern.quote(keyword.trim());
            Criteria keywordCriteria = new Criteria().orOperator(
                    Criteria.where("code").regex(sanitizedKeyword, "i"),
                    Criteria.where("name").regex(sanitizedKeyword, "i")
            );
            criteriaList.add(keywordCriteria);
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Major> majors = mongoTemplate.find(query, Major.class);
        return majors.stream()
                .map(MajorResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public MajorResponse getMajorById(String id) {
        Major major = findMajorEntityById(id);
        return MajorResponse.fromEntity(major);
    }

    @Override
    public MajorResponse getMajorByCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new RuntimeException("Mã ngành không được để trống");
        }
        Major major = majorRepository.findByCode(code.trim())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy ngành đào tạo với mã: " + code));
        return MajorResponse.fromEntity(major);
    }

    @Override
    public MajorResponse createMajor(CreateMajorRequest request) {
        String code = request.getCode().trim();
        if (majorRepository.existsByCode(code)) {
            throw new RuntimeException("Mã ngành '" + code + "' đã tồn tại trong hệ thống");
        }

        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().trim().toUpperCase()
                : "ACTIVE";

        Major major = Major.builder()
                .code(code)
                .name(request.getName().trim())
                .description(request.getDescription())
                .status(status)
                .build();

        Major saved = majorRepository.save(major);
        return MajorResponse.fromEntity(saved);
    }

    @Override
    public MajorResponse updateMajor(String id, UpdateMajorRequest request) {
        Major major = findMajorEntityById(id);

        major.setName(request.getName().trim());
        if (request.getDescription() != null) {
            major.setDescription(request.getDescription());
        }
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            major.setStatus(request.getStatus().trim().toUpperCase());
        }

        Major updated = majorRepository.save(major);
        return MajorResponse.fromEntity(updated);
    }

    @Override
    public MajorResponse deleteMajor(String id) {
        Major major = findMajorEntityById(id);

        // Áp dụng Soft Delete theo quy chuẩn Baseline v4.2
        major.setStatus("INACTIVE");
        Major updated = majorRepository.save(major);
        return MajorResponse.fromEntity(updated);
    }

    private Major findMajorEntityById(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new RuntimeException("ID ngành không hợp lệ: " + id);
        }
        return majorRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy ngành đào tạo với ID: " + id));
    }
}
