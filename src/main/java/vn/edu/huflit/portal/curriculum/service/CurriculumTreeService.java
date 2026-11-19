package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;

@Service
@RequiredArgsConstructor
public class CurriculumTreeService {
    private final CurriculumRepository repository;
    private final MongoTemplate mongo;

    public Curriculum save(String id, String code, Curriculum.SpecializationTree tree) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID CTĐT không hợp lệ");
        Curriculum current = repository.findById(new ObjectId(id))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy CTĐT"));
        if (!"DRAFT".equals(current.getStatus()))
            throw new IllegalArgumentException("Chỉ sửa cây của CTĐT bản nháp; hãy tạo phiên bản mới");
        if (code == null || !code.matches("[A-Z0-9_-]{2,20}")
            || CurriculumTracks.safe(current.getSpecializations()).stream().noneMatch(spec -> code.equals(spec.getCode())))
            throw new IllegalArgumentException("Chuyên ngành không thuộc CTĐT");
        if (tree == null) throw new IllegalArgumentException("Thiếu cấu trúc cây");

        // Materialize every legacy track in one write before introducing explicit trees.
        java.util.Map<String, Curriculum.SpecializationTree> trees = new java.util.LinkedHashMap<>(CurriculumTracks.trees(current));
        if (trees.isEmpty()) for (var spec : CurriculumTracks.safe(current.getSpecializations())) {
            Curriculum selected = CurriculumTracks.select(current, spec.getCode());
            trees.put(spec.getCode(), new Curriculum.SpecializationTree(
                selected.getSemesters().stream().map(semester -> new Curriculum.SemesterItem(semester.getSemesterNumber(),
                    semester.getCourses().stream().map(course -> course.toBuilder().specializationCodes(java.util.List.of()).build()).toList())).toList(),
                selected.getRequirementGroups().stream().map(group -> group.toBuilder()
                    .specializationCodes(java.util.List.of(spec.getCode())).build()).toList()));
        }
        trees.put(code, tree);
        Query query = Query.query(Criteria.where("_id").is(current.getId()).and("status").is("DRAFT")
            .and("revision").is(current.getRevision()));
        Curriculum saved = mongo.findAndModify(query, new Update().set("specializationTrees", trees)
            .set("specializationTrack", "EARLY").set("specializationDecisionSemester", 1)
            .set("updatedAt", new java.util.Date()).inc("revision", 1),
            FindAndModifyOptions.options().returnNew(true), Curriculum.class);
        if (saved == null) throw new IllegalStateException("CTĐT vừa thay đổi; hãy tải lại trước khi lưu");
        return saved;
    }
}
