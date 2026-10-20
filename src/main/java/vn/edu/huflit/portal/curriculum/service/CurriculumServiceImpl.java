package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.dto.*;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.major.model.Major;
import vn.edu.huflit.portal.major.repository.MajorRepository;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CurriculumServiceImpl implements CurriculumService {

    private final CurriculumRepository curriculumRepository;
    private final MajorRepository majorRepository;
    private final CourseRepository courseRepository;
    private final MongoTemplate mongoTemplate;
    private final ChangeLogService changeLogService;

    @Override
    public List<CurriculumResponse> getAllCurriculums(String majorId, String cohort, Integer appliedYear, String status,
            String keyword, String courseCode) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (majorId != null && !majorId.trim().isEmpty()) {
            String cleanMajor = majorId.trim();
            if (ObjectId.isValid(cleanMajor)) {
                criteriaList.add(Criteria.where("majorId").is(new ObjectId(cleanMajor)));
            } else {
                majorRepository.findByCode(cleanMajor).ifPresent(m ->
                        criteriaList.add(Criteria.where("majorId").is(m.getId()))
                );
            }
        }

        if (cohort != null && !cohort.trim().isEmpty()) {
            criteriaList.add(Criteria.where("cohort").is(cohort.trim()));
        }

        if (appliedYear != null) {
            criteriaList.add(Criteria.where("appliedYear").is(appliedYear));
        }

        if (status != null && !status.trim().isEmpty()) {
            criteriaList.add(Criteria.where("status").is(status.trim().toUpperCase()));
        }

        // F39: Tìm kiếm theo keyword không phân biệt hoa thường trên curriculumCode, curriculumName, description
        if (keyword != null && !keyword.trim().isEmpty()) {
            String sanitized = Pattern.quote(keyword.trim());
            Criteria keywordCriteria = new Criteria().orOperator(
                    Criteria.where("curriculumCode").regex(sanitized, "i"),
                    Criteria.where("curriculumName").regex(sanitized, "i"),
                    Criteria.where("description").regex(sanitized, "i"));
            criteriaList.add(keywordCriteria);
        }

        // F39: Lọc các CTĐT chứa học phần cụ thể
        if (courseCode != null && !courseCode.trim().isEmpty()) {
            criteriaList.add(Criteria.where("semesters.courses.courseCode").regex(Pattern.quote(courseCode.trim()), "i"));
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        query.with(Sort.by(Sort.Direction.DESC, "appliedYear", "createdAt"));

        List<Curriculum> curriculums = mongoTemplate.find(query, Curriculum.class);
        return curriculums.stream()
                .map(CurriculumResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public CurriculumResponse getCurriculumById(String id) {
        Curriculum curriculum = findCurriculumEntityById(id);
        return CurriculumResponse.fromEntity(curriculum);
    }

    @Override
    public CurriculumResponse getCurriculumByCode(String curriculumCode) {
        if (curriculumCode == null || curriculumCode.trim().isEmpty()) {
            throw new RuntimeException("Mã chương trình đào tạo không được để trống");
        }
        Curriculum curriculum = curriculumRepository.findByCurriculumCode(curriculumCode.trim())
                .orElseThrow(
                        () -> new RuntimeException("Không tìm thấy chương trình đào tạo với mã: " + curriculumCode));
        return CurriculumResponse.fromEntity(curriculum);
    }

    @Override
    public CurriculumResponse createCurriculum(CreateCurriculumRequest request) {
        String code = request.getCurriculumCode().trim();
        if (curriculumRepository.existsByCurriculumCode(code)) {
            throw new RuntimeException("Mã chương trình đào tạo '" + code + "' đã tồn tại trong hệ thống");
        }

        String majorIdStr = request.getMajorId().trim();
        if (!ObjectId.isValid(majorIdStr)) {
            throw new RuntimeException("ID ngành đào tạo không hợp lệ: " + majorIdStr);
        }
        ObjectId majorObjectId = new ObjectId(majorIdStr);
        if (!majorRepository.existsById(majorObjectId)) {
            throw new RuntimeException("Không tìm thấy ngành đào tạo với ID: " + majorIdStr);
        }

        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().trim().toUpperCase()
                : "ACTIVE";

        String timing = (request.getSpecializationSelectionTiming() != null
                && !request.getSpecializationSelectionTiming().trim().isEmpty())
                        ? request.getSpecializationSelectionTiming().trim().toUpperCase()
                        : "LATER";

        validateSpecializationTiming(timing, request.getSpecializationSelectionSemester());

        // Chuyển đổi cấu trúc lồng nhau (nested structures)
        List<Curriculum.SpecializationItem> specs = new ArrayList<>();
        if (request.getSpecializations() != null) {
            specs = request.getSpecializations().stream()
                    .map(s -> Curriculum.SpecializationItem.builder()
                            .code(s.getCode() != null ? s.getCode().trim().toUpperCase() : null)
                            .name(s.getName() != null ? s.getName().trim() : null)
                            .build())
                    .collect(Collectors.toList());
        }

        List<Curriculum.KnowledgeGroupItem> groups = new ArrayList<>();
        if (request.getKnowledgeGroups() != null) {
            groups = request.getKnowledgeGroups().stream()
                    .map(g -> Curriculum.KnowledgeGroupItem.builder()
                            .groupCode(g.getGroupCode() != null ? g.getGroupCode().trim().toUpperCase() : null)
                            .groupName(g.getGroupName() != null ? g.getGroupName().trim() : null)
                            .order(g.getOrder())
                            .build())
                    .collect(Collectors.toList());
        }

        List<Curriculum.SemesterItem> semesters = new ArrayList<>();
        int computedCredits = 0;
        if (request.getSemesters() != null) {
            for (SemesterDto sDto : request.getSemesters()) {
                List<Curriculum.CurriculumCourseItem> courseItems = new ArrayList<>();
                if (sDto.getCourses() != null) {
                    for (CurriculumCourseDto cDto : sDto.getCourses()) {
                        String cCode = cDto.getCourseCode() != null ? cDto.getCourseCode().trim() : "";
                        String cName = cDto.getCourseName();
                        Integer cCredits = cDto.getCredits();

                        if (!cCode.isEmpty()) {
                            Optional<Course> optCourse = courseRepository.findByCourseCode(cCode);
                            if (optCourse.isPresent()) {
                                Course courseDoc = optCourse.get();
                                if (cName == null || cName.trim().isEmpty()) {
                                    cName = courseDoc.getCourseName();
                                }
                                if (cCredits == null || cCredits <= 0) {
                                    cCredits = courseDoc.getCredits();
                                }
                            }
                        }

                        if (cCredits != null) {
                            computedCredits += cCredits;
                        }

                        courseItems.add(Curriculum.CurriculumCourseItem.builder()
                                .courseCode(cCode)
                                .courseName(cName)
                                .credits(cCredits)
                                .type(cDto.getType() != null ? cDto.getType().trim().toUpperCase() : "MANDATORY")
                                .knowledgeGroupCode(
                                        cDto.getKnowledgeGroupCode() != null ? cDto.getKnowledgeGroupCode().trim().toUpperCase()
                                                : null)
                                .specializationCodes(cDto.getSpecializationCodes() != null
                                        ? new ArrayList<>(cDto.getSpecializationCodes())
                                        : new ArrayList<>())
                                .prerequisiteCourseCodes(cDto.getPrerequisiteCourseCodes() != null
                                        ? new ArrayList<>(cDto.getPrerequisiteCourseCodes())
                                        : new ArrayList<>())
                                .build());
                    }
                }
                semesters.add(Curriculum.SemesterItem.builder()
                        .semesterNumber(sDto.getSemesterNumber())
                        .courses(courseItems)
                        .build());
            }
        }

        int totalCredits = (request.getTotalCredits() != null && request.getTotalCredits() > 0)
                ? request.getTotalCredits()
                : computedCredits;

        Curriculum curriculum = Curriculum.builder()
                .curriculumCode(code)
                .curriculumName(request.getCurriculumName().trim())
                .majorId(majorObjectId)
                .cohort(request.getCohort().trim())
                .appliedYear(request.getAppliedYear())
                .totalCredits(totalCredits)
                .description(request.getDescription())
                .status(status)
                .version(1)
                .clonedFromCurriculumId(null)
                .specializationSelectionTiming(timing)
                .specializationSelectionSemester(request.getSpecializationSelectionSemester())
                .specializations(specs)
                .knowledgeGroups(groups)
                .semesters(semesters)
                .createdBy(getCurrentUserId())
                .build();

        Curriculum saved = curriculumRepository.save(curriculum);

        // F41: Ghi nhận vết kiểm toán khi tạo mới CTĐT (action = CREATE)
        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();
        changes.add(new ChangeLog.FieldChangeItem("curriculumCode", null, saved.getCurriculumCode()));
        changes.add(new ChangeLog.FieldChangeItem("curriculumName", null, saved.getCurriculumName()));
        changes.add(new ChangeLog.FieldChangeItem("cohort", null, saved.getCohort()));
        changes.add(new ChangeLog.FieldChangeItem("appliedYear", null, saved.getAppliedYear()));
        changes.add(new ChangeLog.FieldChangeItem("totalCredits", null, saved.getTotalCredits()));
        changes.add(new ChangeLog.FieldChangeItem("status", null, saved.getStatus()));
        changeLogService.recordChange("Curriculum", saved.getId(), "CREATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(saved);
    }

    @Override
    public CurriculumResponse updateCurriculum(String id, UpdateCurriculumRequest request) {
        Curriculum curriculum = findCurriculumEntityById(id);
        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();

        if (request.getCurriculumName() != null && !request.getCurriculumName().trim().isEmpty()) {
            String newName = request.getCurriculumName().trim();
            if (!Objects.equals(curriculum.getCurriculumName(), newName)) {
                changes.add(new ChangeLog.FieldChangeItem("curriculumName", curriculum.getCurriculumName(), newName));
                curriculum.setCurriculumName(newName);
            }
        }

        if (request.getMajorId() != null && !request.getMajorId().trim().isEmpty()) {
            String majorIdStr = request.getMajorId().trim();
            if (!ObjectId.isValid(majorIdStr)) {
                throw new RuntimeException("ID ngành đào tạo không hợp lệ: " + majorIdStr);
            }
            ObjectId majorObjectId = new ObjectId(majorIdStr);
            if (!majorRepository.existsById(majorObjectId)) {
                throw new RuntimeException("Không tìm thấy ngành đào tạo với ID: " + majorIdStr);
            }
            if (!Objects.equals(curriculum.getMajorId(), majorObjectId)) {
                changes.add(new ChangeLog.FieldChangeItem("majorId",
                        curriculum.getMajorId() != null ? curriculum.getMajorId().toHexString() : null, majorIdStr));
                curriculum.setMajorId(majorObjectId);
            }
        }

        if (request.getCohort() != null && !request.getCohort().trim().isEmpty()) {
            String newCohort = request.getCohort().trim();
            if (!Objects.equals(curriculum.getCohort(), newCohort)) {
                changes.add(new ChangeLog.FieldChangeItem("cohort", curriculum.getCohort(), newCohort));
                curriculum.setCohort(newCohort);
            }
        }

        if (request.getAppliedYear() != null && !Objects.equals(curriculum.getAppliedYear(), request.getAppliedYear())) {
            changes.add(new ChangeLog.FieldChangeItem("appliedYear", curriculum.getAppliedYear(), request.getAppliedYear()));
            curriculum.setAppliedYear(request.getAppliedYear());
        }

        if (request.getTotalCredits() != null && !Objects.equals(curriculum.getTotalCredits(), request.getTotalCredits())) {
            changes.add(new ChangeLog.FieldChangeItem("totalCredits", curriculum.getTotalCredits(), request.getTotalCredits()));
            curriculum.setTotalCredits(request.getTotalCredits());
        }

        if (request.getDescription() != null && !Objects.equals(curriculum.getDescription(), request.getDescription())) {
            changes.add(new ChangeLog.FieldChangeItem("description", curriculum.getDescription(), request.getDescription()));
            curriculum.setDescription(request.getDescription());
        }

        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            String newStatus = request.getStatus().trim().toUpperCase();
            if (!Objects.equals(curriculum.getStatus(), newStatus)) {
                changes.add(new ChangeLog.FieldChangeItem("status", curriculum.getStatus(), newStatus));
                curriculum.setStatus(newStatus);
            }
        }

        if (request.getSpecializationSelectionTiming() != null
                && !request.getSpecializationSelectionTiming().trim().isEmpty()) {
            String newTiming = request.getSpecializationSelectionTiming().trim().toUpperCase();
            if (!Objects.equals(curriculum.getSpecializationSelectionTiming(), newTiming)) {
                changes.add(new ChangeLog.FieldChangeItem("specializationSelectionTiming",
                        curriculum.getSpecializationSelectionTiming(), newTiming));
                curriculum.setSpecializationSelectionTiming(newTiming);
            }
        }

        if (request.getSpecializationSelectionSemester() != null
                && !Objects.equals(curriculum.getSpecializationSelectionSemester(), request.getSpecializationSelectionSemester())) {
            changes.add(new ChangeLog.FieldChangeItem("specializationSelectionSemester",
                    curriculum.getSpecializationSelectionSemester(), request.getSpecializationSelectionSemester()));
            curriculum.setSpecializationSelectionSemester(request.getSpecializationSelectionSemester());
        }

        validateSpecializationTiming(curriculum.getSpecializationSelectionTiming(), curriculum.getSpecializationSelectionSemester());

        if (request.getSpecializations() != null) {
            changes.add(new ChangeLog.FieldChangeItem("specializations",
                    curriculum.getSpecializations() != null ? curriculum.getSpecializations().size() : 0,
                    request.getSpecializations().size()));
            List<Curriculum.SpecializationItem> specs = request.getSpecializations().stream()
                    .map(s -> Curriculum.SpecializationItem.builder()
                            .code(s.getCode() != null ? s.getCode().trim().toUpperCase() : null)
                            .name(s.getName() != null ? s.getName().trim() : null)
                            .build())
                    .collect(Collectors.toList());
            curriculum.setSpecializations(specs);
        }

        if (request.getKnowledgeGroups() != null) {
            changes.add(new ChangeLog.FieldChangeItem("knowledgeGroups",
                    curriculum.getKnowledgeGroups() != null ? curriculum.getKnowledgeGroups().size() : 0,
                    request.getKnowledgeGroups().size()));
            List<Curriculum.KnowledgeGroupItem> groups = request.getKnowledgeGroups().stream()
                    .map(g -> Curriculum.KnowledgeGroupItem.builder()
                            .groupCode(g.getGroupCode() != null ? g.getGroupCode().trim().toUpperCase() : null)
                            .groupName(g.getGroupName() != null ? g.getGroupName().trim() : null)
                            .order(g.getOrder())
                            .build())
                    .collect(Collectors.toList());
            curriculum.setKnowledgeGroups(groups);
        }

        if (request.getSemesters() != null) {
            changes.add(new ChangeLog.FieldChangeItem("semesters",
                    curriculum.getSemesters() != null ? curriculum.getSemesters().size() : 0,
                    request.getSemesters().size()));
            List<Curriculum.SemesterItem> semesters = new ArrayList<>();
            for (SemesterDto sDto : request.getSemesters()) {
                List<Curriculum.CurriculumCourseItem> courseItems = new ArrayList<>();
                if (sDto.getCourses() != null) {
                    for (CurriculumCourseDto cDto : sDto.getCourses()) {
                        String cCode = cDto.getCourseCode() != null ? cDto.getCourseCode().trim() : "";
                        String cName = cDto.getCourseName();
                        Integer cCredits = cDto.getCredits();

                        if (!cCode.isEmpty()) {
                            Optional<Course> optCourse = courseRepository.findByCourseCode(cCode);
                            if (optCourse.isPresent()) {
                                Course courseDoc = optCourse.get();
                                if (cName == null || cName.trim().isEmpty()) {
                                    cName = courseDoc.getCourseName();
                                }
                                if (cCredits == null || cCredits <= 0) {
                                    cCredits = courseDoc.getCredits();
                                }
                            }
                        }

                        courseItems.add(Curriculum.CurriculumCourseItem.builder()
                                .courseCode(cCode)
                                .courseName(cName)
                                .credits(cCredits)
                                .type(cDto.getType() != null ? cDto.getType().trim().toUpperCase() : "MANDATORY")
                                .knowledgeGroupCode(
                                        cDto.getKnowledgeGroupCode() != null ? cDto.getKnowledgeGroupCode().trim().toUpperCase()
                                                : null)
                                .specializationCodes(cDto.getSpecializationCodes() != null
                                        ? new ArrayList<>(cDto.getSpecializationCodes())
                                        : new ArrayList<>())
                                .prerequisiteCourseCodes(cDto.getPrerequisiteCourseCodes() != null
                                        ? new ArrayList<>(cDto.getPrerequisiteCourseCodes())
                                        : new ArrayList<>())
                                .build());
                    }
                }
                semesters.add(Curriculum.SemesterItem.builder()
                        .semesterNumber(sDto.getSemesterNumber())
                        .courses(courseItems)
                        .build());
            }
            semesters.sort(Comparator.comparingInt(Curriculum.SemesterItem::getSemesterNumber));
            curriculum.setSemesters(semesters);
            recalculateTotalCredits(curriculum);
        }

        Curriculum updated = curriculumRepository.save(curriculum);

        // F41: Ghi nhận vết kiểm toán khi cập nhật CTĐT (action = UPDATE)
        if (!changes.isEmpty()) {
            changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());
        }

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse cloneCurriculum(String id, CloneCurriculumRequest request) {
        Curriculum source = findCurriculumEntityById(id);

        String newCode = request.getTargetCurriculumCode();
        if (newCode == null || newCode.isEmpty()) {
            throw new RuntimeException("Mã chương trình đào tạo mới không được để trống khi thực hiện clone");
        }

        if (curriculumRepository.existsByCurriculumCode(newCode)) {
            throw new RuntimeException("Mã chương trình đào tạo '" + newCode + "' đã tồn tại trong hệ thống");
        }

        String targetName = request.getTargetCurriculumName();
        if (targetName == null || targetName.isEmpty()) {
            targetName = source.getCurriculumName() + " (Bản sao)";
        }

        String targetCohort = request.getTargetCohort();
        if (targetCohort == null || targetCohort.isEmpty()) {
            targetCohort = source.getCohort();
        }

        Integer targetAppliedYear = request.getTargetAppliedYear();
        if (targetAppliedYear == null) {
            targetAppliedYear = source.getAppliedYear();
        }

        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().trim().toUpperCase()
                : "ACTIVE";

        String description = request.getDescription() != null
                ? request.getDescription()
                : "Bản sao từ " + source.getCurriculumCode();

        // BR03: THỰC HIỆN DEEP COPY TOÀN DIỆN MỌI CẤU TRÚC LỒNG NHAU ĐỂ ĐẢM BẢO TÍNH ĐỘC LẬP TUYỆT ĐỐI
        List<Curriculum.SpecializationItem> clonedSpecs = new ArrayList<>();
        if (source.getSpecializations() != null) {
            for (Curriculum.SpecializationItem sp : source.getSpecializations()) {
                clonedSpecs.add(Curriculum.SpecializationItem.builder()
                        .code(sp.getCode())
                        .name(sp.getName())
                        .build());
            }
        }

        List<Curriculum.KnowledgeGroupItem> clonedGroups = new ArrayList<>();
        if (source.getKnowledgeGroups() != null) {
            for (Curriculum.KnowledgeGroupItem kg : source.getKnowledgeGroups()) {
                clonedGroups.add(Curriculum.KnowledgeGroupItem.builder()
                        .groupCode(kg.getGroupCode())
                        .groupName(kg.getGroupName())
                        .order(kg.getOrder())
                        .build());
            }
        }

        List<Curriculum.SemesterItem> clonedSemesters = new ArrayList<>();
        if (source.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : source.getSemesters()) {
                List<Curriculum.CurriculumCourseItem> clonedCourses = new ArrayList<>();
                if (sem.getCourses() != null) {
                    for (Curriculum.CurriculumCourseItem c : sem.getCourses()) {
                        clonedCourses.add(Curriculum.CurriculumCourseItem.builder()
                                .courseCode(c.getCourseCode())
                                .courseName(c.getCourseName())
                                .credits(c.getCredits())
                                .type(c.getType())
                                .knowledgeGroupCode(c.getKnowledgeGroupCode())
                                .specializationCodes(
                                        c.getSpecializationCodes() != null ? new ArrayList<>(c.getSpecializationCodes())
                                                : new ArrayList<>())
                                .prerequisiteCourseCodes(c.getPrerequisiteCourseCodes() != null
                                        ? new ArrayList<>(c.getPrerequisiteCourseCodes())
                                        : new ArrayList<>())
                                .build());
                    }
                }
                clonedSemesters.add(Curriculum.SemesterItem.builder()
                        .semesterNumber(sem.getSemesterNumber())
                        .courses(clonedCourses)
                        .build());
            }
        }

        Curriculum clonedCurriculum = Curriculum.builder()
                .curriculumCode(newCode)
                .curriculumName(targetName)
                .majorId(source.getMajorId())
                .cohort(targetCohort)
                .appliedYear(targetAppliedYear)
                .totalCredits(source.getTotalCredits())
                .description(description)
                .status(status)
                .version(1)
                .clonedFromCurriculumId(source.getId()) // Lưu ID CTĐT gốc
                .specializationSelectionTiming(source.getSpecializationSelectionTiming())
                .specializationSelectionSemester(source.getSpecializationSelectionSemester())
                .specializations(clonedSpecs)
                .knowledgeGroups(clonedGroups)
                .semesters(clonedSemesters)
                .createdBy(getCurrentUserId())
                .build();

        Curriculum saved = curriculumRepository.save(clonedCurriculum);

        // F41: Ghi nhận vết kiểm toán khi Deep Clone CTĐT (action = CLONE)
        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();
        changes.add(new ChangeLog.FieldChangeItem("sourceCurriculumId", source.getId().toHexString(), saved.getId().toHexString()));
        changes.add(new ChangeLog.FieldChangeItem("curriculumCode", source.getCurriculumCode(), saved.getCurriculumCode()));
        changes.add(new ChangeLog.FieldChangeItem("cohort", source.getCohort(), saved.getCohort()));
        changes.add(new ChangeLog.FieldChangeItem("version", source.getVersion(), saved.getVersion()));
        changeLogService.recordChange("Curriculum", saved.getId(), "CLONE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(saved);
    }

    @Override
    public CurriculumResponse deleteCurriculum(String id) {
        Curriculum curriculum = findCurriculumEntityById(id);
        String oldStatus = curriculum.getStatus();

        // BR02: TUYỆT ĐỐI KHÔNG XÓA CỨNG DỮ LIỆU CTĐT, ÁP DỤNG SOFT-DELETE BẰNG STATUS ARCHIVED / INACTIVE
        curriculum.setStatus("ARCHIVED");
        Curriculum updated = curriculumRepository.save(curriculum);

        // F41: Ghi nhận vết kiểm toán khi chuyển status sang ARCHIVED (action = UPDATE)
        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("status", oldStatus, "ARCHIVED")
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    // ====================================================================================
    // F10: QUẢN LÝ NHÓM KIẾN THỨC (KNOWLEDGE GROUPS - BR04)
    // ====================================================================================

    @Override
    public CurriculumResponse replaceKnowledgeGroups(String id, List<KnowledgeGroupDto> knowledgeGroups) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (knowledgeGroups == null) {
            knowledgeGroups = new ArrayList<>();
        }

        Set<String> seenCodes = new HashSet<>();
        List<Curriculum.KnowledgeGroupItem> items = new ArrayList<>();
        for (KnowledgeGroupDto dto : knowledgeGroups) {
            if (dto.getGroupCode() == null || dto.getGroupCode().trim().isEmpty()) {
                throw new RuntimeException("Mã nhóm kiến thức không được để trống");
            }
            String code = dto.getGroupCode().trim().toUpperCase();
            if (!seenCodes.add(code)) {
                throw new RuntimeException("Trùng lặp mã nhóm kiến thức '" + code + "' trong danh sách");
            }
            items.add(Curriculum.KnowledgeGroupItem.builder()
                    .groupCode(code)
                    .groupName(dto.getGroupName() != null ? dto.getGroupName().trim() : "")
                    .order(dto.getOrder())
                    .build());
        }

        // Kiểm tra không loại bỏ nhóm kiến thức mà đang có học phần trực thuộc
        if (curriculum.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : curriculum.getSemesters()) {
                if (sem.getCourses() != null) {
                    for (Curriculum.CurriculumCourseItem c : sem.getCourses()) {
                        if (c.getKnowledgeGroupCode() != null && !c.getKnowledgeGroupCode().trim().isEmpty()) {
                            if (!seenCodes.contains(c.getKnowledgeGroupCode().trim().toUpperCase())) {
                                throw new RuntimeException("Không thể cập nhật danh sách vì học phần '" + c.getCourseCode()
                                        + "' đang thuộc nhóm kiến thức '" + c.getKnowledgeGroupCode() + "' bị loại bỏ");
                            }
                        }
                    }
                }
            }
        }

        int oldSize = curriculum.getKnowledgeGroups() != null ? curriculum.getKnowledgeGroups().size() : 0;
        curriculum.setKnowledgeGroups(items);
        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("knowledgeGroups", oldSize, items.size())
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse addKnowledgeGroup(String id, KnowledgeGroupDto dto) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (dto.getGroupCode() == null || dto.getGroupCode().trim().isEmpty()) {
            throw new RuntimeException("Mã nhóm kiến thức không được để trống");
        }
        String code = dto.getGroupCode().trim().toUpperCase();

        if (curriculum.getKnowledgeGroups() == null) {
            curriculum.setKnowledgeGroups(new ArrayList<>());
        }

        boolean exists = curriculum.getKnowledgeGroups().stream()
                .anyMatch(g -> g.getGroupCode().equalsIgnoreCase(code));
        if (exists) {
            throw new RuntimeException("Mã nhóm kiến thức '" + code + "' đã tồn tại trong chương trình đào tạo");
        }

        curriculum.getKnowledgeGroups().add(Curriculum.KnowledgeGroupItem.builder()
                .groupCode(code)
                .groupName(dto.getGroupName() != null ? dto.getGroupName().trim() : "")
                .order(dto.getOrder())
                .build());

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("knowledgeGroups", null, "Added group: " + code)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse updateKnowledgeGroup(String id, String groupCode, UpdateKnowledgeGroupRequest request) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (groupCode == null || groupCode.trim().isEmpty()) {
            throw new RuntimeException("Mã nhóm kiến thức không được để trống");
        }
        String targetCode = groupCode.trim().toUpperCase();

        if (curriculum.getKnowledgeGroups() == null) {
            curriculum.setKnowledgeGroups(new ArrayList<>());
        }

        Curriculum.KnowledgeGroupItem item = curriculum.getKnowledgeGroups().stream()
                .filter(g -> g.getGroupCode().equalsIgnoreCase(targetCode))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Không tìm thấy nhóm kiến thức với mã: " + groupCode));

        String oldName = item.getGroupName();
        if (request.getGroupName() != null && !request.getGroupName().trim().isEmpty()) {
            item.setGroupName(request.getGroupName().trim());
        }
        if (request.getOrder() != null) {
            item.setOrder(request.getOrder());
        }

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("knowledgeGroups." + targetCode, oldName, item.getGroupName())
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse deleteKnowledgeGroup(String id, String groupCode) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (groupCode == null || groupCode.trim().isEmpty()) {
            throw new RuntimeException("Mã nhóm kiến thức không được để trống");
        }
        String targetCode = groupCode.trim().toUpperCase();

        // Kiểm tra xem có học phần nào trong bất kỳ học kỳ nào đang trực thuộc nhóm này không
        boolean isReferenced = curriculum.getSemesters() != null && curriculum.getSemesters().stream()
                .filter(s -> s.getCourses() != null)
                .flatMap(s -> s.getCourses().stream())
                .anyMatch(c -> c.getKnowledgeGroupCode() != null && targetCode.equalsIgnoreCase(c.getKnowledgeGroupCode().trim()));

        if (isReferenced) {
            throw new RuntimeException("Không thể xóa nhóm kiến thức '" + groupCode + "' vì đang có học phần trong CTĐT trực thuộc nhóm này");
        }

        if (curriculum.getKnowledgeGroups() != null) {
            curriculum.getKnowledgeGroups().removeIf(g -> g.getGroupCode().equalsIgnoreCase(targetCode));
        }

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("knowledgeGroups", "Deleted group: " + targetCode, null)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    // ====================================================================================
    // F16 & F17: CHUYÊN NGÀNH & THỜI ĐIỂM CHỌN ĐỊNH HƯỚNG (BR04, BR18)
    // ====================================================================================

    @Override
    public CurriculumResponse configureSpecializations(String id, ConfigureSpecializationsRequest request) {
        Curriculum curriculum = findCurriculumEntityById(id);

        String timing = request.getSpecializationSelectionTiming();
        Integer semester = request.getSpecializationSelectionSemester();
        validateSpecializationTiming(timing, semester);

        String oldTiming = curriculum.getSpecializationSelectionTiming();
        Integer oldSemester = curriculum.getSpecializationSelectionSemester();
        int oldSize = curriculum.getSpecializations() != null ? curriculum.getSpecializations().size() : 0;

        if (timing != null && !timing.trim().isEmpty()) {
            curriculum.setSpecializationSelectionTiming(timing.trim().toUpperCase());
        }
        curriculum.setSpecializationSelectionSemester(semester);

        int newSize = oldSize;
        if (request.getSpecializations() != null) {
            Set<String> seen = new HashSet<>();
            List<Curriculum.SpecializationItem> items = new ArrayList<>();
            for (SpecializationDto dto : request.getSpecializations()) {
                if (dto.getCode() == null || dto.getCode().trim().isEmpty()) {
                    throw new RuntimeException("Mã chuyên ngành không được để trống");
                }
                String code = dto.getCode().trim().toUpperCase();
                if (!seen.add(code)) {
                    throw new RuntimeException("Trùng lặp mã chuyên ngành '" + code + "' trong danh sách");
                }
                items.add(Curriculum.SpecializationItem.builder()
                        .code(code)
                        .name(dto.getName() != null ? dto.getName().trim() : "")
                        .build());
            }
            curriculum.setSpecializations(items);
            newSize = items.size();
        }

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();
        changes.add(new ChangeLog.FieldChangeItem("specializationSelectionTiming", oldTiming, curriculum.getSpecializationSelectionTiming()));
        changes.add(new ChangeLog.FieldChangeItem("specializationSelectionSemester", oldSemester, curriculum.getSpecializationSelectionSemester()));
        changes.add(new ChangeLog.FieldChangeItem("specializations", oldSize, newSize));
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse addSpecialization(String id, SpecializationDto dto) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (dto.getCode() == null || dto.getCode().trim().isEmpty()) {
            throw new RuntimeException("Mã chuyên ngành không được để trống");
        }
        String code = dto.getCode().trim().toUpperCase();

        if (curriculum.getSpecializations() == null) {
            curriculum.setSpecializations(new ArrayList<>());
        }

        boolean exists = curriculum.getSpecializations().stream()
                .anyMatch(s -> s.getCode().equalsIgnoreCase(code));
        if (exists) {
            throw new RuntimeException("Mã chuyên ngành '" + code + "' đã tồn tại trong chương trình đào tạo");
        }

        curriculum.getSpecializations().add(Curriculum.SpecializationItem.builder()
                .code(code)
                .name(dto.getName() != null ? dto.getName().trim() : "")
                .build());

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("specializations", null, "Added specialization: " + code)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse deleteSpecialization(String id, String specCode) {
        Curriculum curriculum = findCurriculumEntityById(id);
        if (specCode == null || specCode.trim().isEmpty()) {
            throw new RuntimeException("Mã chuyên ngành không được để trống");
        }
        String targetCode = specCode.trim().toUpperCase();

        if (curriculum.getSpecializations() != null) {
            curriculum.getSpecializations().removeIf(s -> s.getCode().equalsIgnoreCase(targetCode));
        }

        // Tự động dọn dẹp mã chuyên ngành khỏi danh sách specializationCodes của các học phần
        if (curriculum.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : curriculum.getSemesters()) {
                if (sem.getCourses() != null) {
                    for (Curriculum.CurriculumCourseItem c : sem.getCourses()) {
                        if (c.getSpecializationCodes() != null) {
                            c.getSpecializationCodes().removeIf(sc -> sc.equalsIgnoreCase(targetCode));
                        }
                    }
                }
            }
        }

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("specializations", "Deleted specialization: " + targetCode, null)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    // ====================================================================================
    // F11, F12, F13, F14: QUẢN LÝ HỌC PHẦN TRONG CTĐT (BR04, BR05)
    // ====================================================================================

    @Override
    public CurriculumResponse addCourseToCurriculum(String id, AddCurriculumCourseRequest request) {
        Curriculum curriculum = findCurriculumEntityById(id);

        String courseCode = request.getCourseCode().trim();

        // F11: Kiểm tra học phần tồn tại trong hệ thống courses collection
        Course courseDoc = courseRepository.findByCourseCode(courseCode)
                .orElseThrow(() -> new RuntimeException("Học phần với mã '" + courseCode + "' không tồn tại trong danh mục học phần hệ thống"));

        // F11: Đảm bảo cùng một học phần không được thêm trùng lặp trong CTĐT
        if (curriculum.getSemesters() == null) {
            curriculum.setSemesters(new ArrayList<>());
        }
        boolean existsInCurriculum = curriculum.getSemesters().stream()
                .filter(s -> s.getCourses() != null)
                .flatMap(s -> s.getCourses().stream())
                .anyMatch(c -> c.getCourseCode().equalsIgnoreCase(courseCode));
        if (existsInCurriculum) {
            throw new RuntimeException("Học phần '" + courseCode + "' đã tồn tại trong chương trình đào tạo");
        }

        // Tự động đồng bộ courseName và credits nếu request bỏ trống
        String courseName = (request.getCourseName() != null && !request.getCourseName().trim().isEmpty())
                ? request.getCourseName().trim()
                : courseDoc.getCourseName();
        Integer credits = (request.getCredits() != null && request.getCredits() > 0)
                ? request.getCredits()
                : courseDoc.getCredits();

        // F12: Loại học phần MANDATORY hoặc ELECTIVE
        String type = request.getType().trim().toUpperCase();
        if (!"MANDATORY".equals(type) && !"ELECTIVE".equals(type)) {
            throw new RuntimeException("Loại học phần phải là MANDATORY hoặc ELECTIVE");
        }

        // F10, F12: Ràng buộc nhóm kiến thức
        String kgCode = null;
        if (request.getKnowledgeGroupCode() != null && !request.getKnowledgeGroupCode().trim().isEmpty()) {
            kgCode = request.getKnowledgeGroupCode().trim().toUpperCase();
            String finalKgCode = kgCode;
            boolean kgExists = curriculum.getKnowledgeGroups() != null && curriculum.getKnowledgeGroups().stream()
                    .anyMatch(g -> g.getGroupCode().equalsIgnoreCase(finalKgCode));
            if (!kgExists) {
                throw new RuntimeException("Nhóm kiến thức '" + kgCode + "' không tồn tại trong chương trình đào tạo");
            }
        }

        // F16: Ràng buộc chuyên ngành / định hướng
        List<String> specCodes = new ArrayList<>();
        if (request.getSpecializationCodes() != null) {
            for (String sc : request.getSpecializationCodes()) {
                if (sc != null && !sc.trim().isEmpty()) {
                    String cleanCode = sc.trim().toUpperCase();
                    boolean scExists = curriculum.getSpecializations() != null && curriculum.getSpecializations().stream()
                            .anyMatch(s -> s.getCode().equalsIgnoreCase(cleanCode));
                    if (!scExists) {
                        throw new RuntimeException("Chuyên ngành / định hướng '" + cleanCode + "' không tồn tại trong chương trình đào tạo");
                    }
                    specCodes.add(cleanCode);
                }
            }
        }

        // F14: Ràng buộc môn tiên quyết (không được tự tiên quyết, phải tồn tại trong CourseRepository)
        List<String> prereqCodes = new ArrayList<>();
        if (request.getPrerequisiteCourseCodes() != null) {
            for (String pc : request.getPrerequisiteCourseCodes()) {
                if (pc != null && !pc.trim().isEmpty()) {
                    String cleanPrereq = pc.trim();
                    if (cleanPrereq.equalsIgnoreCase(courseCode)) {
                        throw new RuntimeException("Học phần không thể đặt chính nó làm môn học tiên quyết");
                    }
                    if (!courseRepository.existsByCourseCode(cleanPrereq)) {
                        throw new RuntimeException("Môn học tiên quyết '" + cleanPrereq + "' không tồn tại trong danh mục học phần");
                    }
                    prereqCodes.add(cleanPrereq);
                }
            }
        }

        // F13: Phân bổ học kỳ (tự động tạo SemesterItem nếu chưa có)
        int semNum = request.getSemesterNumber();
        Curriculum.SemesterItem targetSemester = curriculum.getSemesters().stream()
                .filter(s -> s.getSemesterNumber() != null && s.getSemesterNumber() == semNum)
                .findFirst()
                .orElseGet(() -> {
                    Curriculum.SemesterItem newSem = Curriculum.SemesterItem.builder()
                            .semesterNumber(semNum)
                            .courses(new ArrayList<>())
                            .build();
                    curriculum.getSemesters().add(newSem);
                    return newSem;
                });

        if (targetSemester.getCourses() == null) {
            targetSemester.setCourses(new ArrayList<>());
        }

        targetSemester.getCourses().add(Curriculum.CurriculumCourseItem.builder()
                .courseCode(courseCode)
                .courseName(courseName)
                .credits(credits)
                .type(type)
                .knowledgeGroupCode(kgCode)
                .specializationCodes(specCodes)
                .prerequisiteCourseCodes(prereqCodes)
                .build());

        curriculum.getSemesters().sort(Comparator.comparingInt(Curriculum.SemesterItem::getSemesterNumber));
        recalculateTotalCredits(curriculum);

        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("courses", null, "Added course: " + courseCode + " to semester " + semNum)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse updateCourseInCurriculum(String id, String courseCode, UpdateCurriculumCourseRequest request) {
        Curriculum curriculum = findCurriculumEntityById(id);
        String targetCode = courseCode.trim();

        Curriculum.SemesterItem currentSemester = null;
        Curriculum.CurriculumCourseItem currentCourse = null;

        if (curriculum.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : curriculum.getSemesters()) {
                if (sem.getCourses() != null) {
                    for (Curriculum.CurriculumCourseItem c : sem.getCourses()) {
                        if (c.getCourseCode().equalsIgnoreCase(targetCode)) {
                            currentSemester = sem;
                            currentCourse = c;
                            break;
                        }
                    }
                }
                if (currentCourse != null) break;
            }
        }

        if (currentCourse == null) {
            throw new RuntimeException("Không tìm thấy học phần '" + targetCode + "' trong chương trình đào tạo");
        }

        if (request.getType() != null && !request.getType().trim().isEmpty()) {
            String type = request.getType().trim().toUpperCase();
            if (!"MANDATORY".equals(type) && !"ELECTIVE".equals(type)) {
                throw new RuntimeException("Loại học phần phải là MANDATORY hoặc ELECTIVE");
            }
            currentCourse.setType(type);
        }

        if (request.getKnowledgeGroupCode() != null) {
            String kgCode = request.getKnowledgeGroupCode().trim().toUpperCase();
            if (!kgCode.isEmpty()) {
                boolean kgExists = curriculum.getKnowledgeGroups() != null && curriculum.getKnowledgeGroups().stream()
                        .anyMatch(g -> g.getGroupCode().equalsIgnoreCase(kgCode));
                if (!kgExists) {
                    throw new RuntimeException("Nhóm kiến thức '" + kgCode + "' không tồn tại trong chương trình đào tạo");
                }
                currentCourse.setKnowledgeGroupCode(kgCode);
            } else {
                currentCourse.setKnowledgeGroupCode(null);
            }
        }

        if (request.getSpecializationCodes() != null) {
            List<String> specCodes = new ArrayList<>();
            for (String sc : request.getSpecializationCodes()) {
                if (sc != null && !sc.trim().isEmpty()) {
                    String cleanCode = sc.trim().toUpperCase();
                    boolean scExists = curriculum.getSpecializations() != null && curriculum.getSpecializations().stream()
                            .anyMatch(s -> s.getCode().equalsIgnoreCase(cleanCode));
                    if (!scExists) {
                        throw new RuntimeException("Chuyên ngành / định hướng '" + cleanCode + "' không tồn tại trong chương trình đào tạo");
                    }
                    specCodes.add(cleanCode);
                }
            }
            currentCourse.setSpecializationCodes(specCodes);
        }

        if (request.getPrerequisiteCourseCodes() != null) {
            List<String> prereqCodes = new ArrayList<>();
            for (String pc : request.getPrerequisiteCourseCodes()) {
                if (pc != null && !pc.trim().isEmpty()) {
                    String cleanPrereq = pc.trim();
                    if (cleanPrereq.equalsIgnoreCase(targetCode)) {
                        throw new RuntimeException("Học phần không thể đặt chính nó làm môn học tiên quyết");
                    }
                    if (!courseRepository.existsByCourseCode(cleanPrereq)) {
                        throw new RuntimeException("Môn học tiên quyết '" + cleanPrereq + "' không tồn tại trong danh mục học phần");
                    }
                    prereqCodes.add(cleanPrereq);
                }
            }
            currentCourse.setPrerequisiteCourseCodes(prereqCodes);
        }

        // F13: Di chuyển môn học sang học kỳ khác nếu requested
        if (request.getSemesterNumber() != null && request.getSemesterNumber() != currentSemester.getSemesterNumber()) {
            int newSemNum = request.getSemesterNumber();
            currentSemester.getCourses().remove(currentCourse);

            Curriculum.SemesterItem targetSemester = curriculum.getSemesters().stream()
                    .filter(s -> s.getSemesterNumber() != null && s.getSemesterNumber() == newSemNum)
                    .findFirst()
                    .orElseGet(() -> {
                        Curriculum.SemesterItem newSem = Curriculum.SemesterItem.builder()
                                .semesterNumber(newSemNum)
                                .courses(new ArrayList<>())
                                .build();
                        curriculum.getSemesters().add(newSem);
                        return newSem;
                    });

            if (targetSemester.getCourses() == null) {
                targetSemester.setCourses(new ArrayList<>());
            }
            targetSemester.getCourses().add(currentCourse);
            curriculum.getSemesters().sort(Comparator.comparingInt(Curriculum.SemesterItem::getSemesterNumber));
        }

        // Tự động dọn dẹp các học kỳ rỗng nếu không còn môn học nào
        if (curriculum.getSemesters() != null) {
            curriculum.getSemesters().removeIf(s -> s.getCourses() == null || s.getCourses().isEmpty());
        }

        recalculateTotalCredits(curriculum);
        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("courses." + targetCode,
                        "semester " + currentSemester.getSemesterNumber(),
                        request.getSemesterNumber() != null ? "semester " + request.getSemesterNumber() : "updated configuration")
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    @Override
    public CurriculumResponse deleteCourseFromCurriculum(String id, String courseCode) {
        Curriculum curriculum = findCurriculumEntityById(id);
        String targetCode = courseCode.trim();

        boolean removed = false;
        if (curriculum.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : curriculum.getSemesters()) {
                if (sem.getCourses() != null) {
                    removed = sem.getCourses().removeIf(c -> c.getCourseCode().equalsIgnoreCase(targetCode));
                    if (removed) break;
                }
            }
        }

        if (!removed) {
            throw new RuntimeException("Không tìm thấy học phần '" + targetCode + "' trong chương trình đào tạo");
        }

        // Xóa môn này khỏi tiên quyết của các môn khác trong CTĐT
        if (curriculum.getSemesters() != null) {
            for (Curriculum.SemesterItem sem : curriculum.getSemesters()) {
                if (sem.getCourses() != null) {
                    for (Curriculum.CurriculumCourseItem c : sem.getCourses()) {
                        if (c.getPrerequisiteCourseCodes() != null) {
                            c.getPrerequisiteCourseCodes().removeIf(p -> p.equalsIgnoreCase(targetCode));
                        }
                    }
                }
            }
        }

        // Tự động dọn dẹp các học kỳ rỗng nếu không còn môn học nào
        if (curriculum.getSemesters() != null) {
            curriculum.getSemesters().removeIf(s -> s.getCourses() == null || s.getCourses().isEmpty());
        }

        recalculateTotalCredits(curriculum);
        Curriculum updated = curriculumRepository.save(curriculum);

        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("courses", "Removed course: " + targetCode, null)
        );
        changeLogService.recordChange("Curriculum", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CurriculumResponse.fromEntity(updated);
    }

    // ====================================================================================
    // PRIVATE HELPER METHODS
    // ====================================================================================

    private void validateSpecializationTiming(String timing, Integer semester) {
        if (timing == null || timing.trim().isEmpty()) {
            return;
        }
        String normalized = timing.trim().toUpperCase();
        if (!"EARLY".equals(normalized) && !"LATER".equals(normalized)) {
            throw new RuntimeException("Thời điểm chọn chuyên ngành (specializationSelectionTiming) phải là 'EARLY' hoặc 'LATER'");
        }
        if ("LATER".equals(normalized)) {
            if (semester == null || semester < 2) {
                throw new RuntimeException("Khi chọn định hướng theo giai đoạn sau (LATER), học kỳ bắt đầu phân nhánh (specializationSelectionSemester) phải từ học kỳ 2 trở lên");
            }
        }
    }

    private void recalculateTotalCredits(Curriculum curriculum) {
        if (curriculum.getSemesters() == null) {
            curriculum.setTotalCredits(0);
            return;
        }
        int sum = curriculum.getSemesters().stream()
                .filter(s -> s.getCourses() != null)
                .flatMap(s -> s.getCourses().stream())
                .mapToInt(c -> c.getCredits() != null ? c.getCredits() : 0)
                .sum();
        curriculum.setTotalCredits(sum);
    }

    private Curriculum findCurriculumEntityById(String id) {
        if (id == null || !ObjectId.isValid(id.trim())) {
            throw new RuntimeException("ID chương trình đào tạo không hợp lệ: " + id);
        }
        return curriculumRepository.findById(new ObjectId(id.trim()))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy chương trình đào tạo với ID: " + id));
    }

    private ObjectId getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return user.getId();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
