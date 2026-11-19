package vn.edu.huflit.portal.academic.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.CurriculumTracks;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class StudentCurriculumAssignmentService {
    private final UserRepository users;
    private final CurriculumRepository curricula;
    private final MongoTemplate mongo;
    private final vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService validator;

    public User assign(String studentId, String curriculumId, String code) {
        if (!ObjectId.isValid(studentId) || !ObjectId.isValid(curriculumId) || code == null || code.isBlank())
            throw new IllegalArgumentException("Sinh viên, CTĐT và chuyên ngành phải hợp lệ");
        User student = users.findById(new ObjectId(studentId))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sinh viên"));
        if (!"STUDENT".equals(student.getRole())) throw new IllegalArgumentException("Tài khoản không phải sinh viên");
        ObjectId target = new ObjectId(curriculumId);
        if ((student.getCurriculumId() != null && !target.equals(student.getCurriculumId()))
            || (student.getChosenSpecializationCode() != null && !student.getChosenSpecializationCode().isBlank()
                && !code.equals(student.getChosenSpecializationCode())))
            throw new IllegalArgumentException("CTĐT và chuyên ngành đã gán cố định; không được chuyển phiên bản hoặc chuyên ngành");
        var curriculum = curricula.findById(target)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy CTĐT"));
        if (!target.equals(student.getCurriculumId()) || student.getChosenSpecializationCode() == null
            || student.getChosenSpecializationCode().isBlank()) {
            if (!"ACTIVE".equals(curriculum.getStatus()))
                throw new IllegalArgumentException("Chỉ gán CTĐT đã công bố đang áp dụng");
        }
        CurriculumTracks.validateStudentScope(curriculum, student);
        CurriculumTracks.select(curriculum, code);
        if (CurriculumTracks.safe(curriculum.getSpecializations()).isEmpty())
            throw new IllegalArgumentException("CTĐT chưa có chuyên ngành để gán");
        if (target.equals(student.getCurriculumId()) && code.equals(student.getChosenSpecializationCode())) return student;
        var validation = validator.validate(curriculum);
        if (!validation.valid())
            throw new IllegalArgumentException("CTĐT chưa có đủ cây hợp lệ để gán: " + String.join(", ", validation.errors()));
        Query query = Query.query(Criteria.where("_id").is(student.getId()).and("role").is("STUDENT")
            .and("majorId").is(student.getMajorId()).and("cohort").is(student.getCohort())
            .and("curriculumId").is(student.getCurriculumId())
            .and("chosenSpecializationCode").is(student.getChosenSpecializationCode()));
        User saved = mongo.findAndModify(query, new Update().set("curriculumId", target)
            .set("chosenSpecializationCode", code).set("updatedAt", new java.util.Date()),
            FindAndModifyOptions.options().returnNew(true), User.class);
        if (saved == null) throw new IllegalStateException("Thông tin gán đã thay đổi; vui lòng tải lại");
        return saved;
    }
}
