package vn.edu.huflit.portal.curriculum.service;

import vn.edu.huflit.portal.curriculum.dto.*;

import java.util.List;

public interface CurriculumService {
    /**
     * F04 & F39: Xem danh sách & tìm kiếm nâng cao CTĐT.
     * Hỗ trợ lọc theo majorId (hoặc majorCode), cohort, appliedYear, status, keyword và courseCode.
     */
    List<CurriculumResponse> getAllCurriculums(String majorId, String cohort, Integer appliedYear, String status, String keyword, String courseCode);

    default List<CurriculumResponse> getAllCurriculums(String majorId, String cohort, Integer appliedYear, String status, String keyword) {
        return getAllCurriculums(majorId, cohort, appliedYear, status, keyword, null);
    }

    /**
     * F08: Xem chi tiết CTĐT theo ID.
     */
    CurriculumResponse getCurriculumById(String id);

    /**
     * F08: Xem chi tiết CTĐT theo mã CTĐT (business key).
     */
    CurriculumResponse getCurriculumByCode(String curriculumCode);

    /**
     * F05: Tạo mới CTĐT.
     */
    CurriculumResponse createCurriculum(CreateCurriculumRequest request);

    /**
     * F06: Chỉnh sửa thông tin CTĐT (mã CTĐT là bất biến).
     */
    CurriculumResponse updateCurriculum(String id, UpdateCurriculumRequest request);

    /**
     * F07: Deep Clone CTĐT từ bản gốc sang khóa/mã mới hoàn toàn độc lập (BR03).
     */
    CurriculumResponse cloneCurriculum(String id, CloneCurriculumRequest request);

    /**
     * F09: Soft Delete / Lưu trữ CTĐT (status = ARCHIVED hoặc INACTIVE, không xóa cứng - BR02).
     */
    CurriculumResponse deleteCurriculum(String id);

    /**
     * F10: Cập nhật / Sắp xếp lại toàn bộ danh sách nhóm kiến thức.
     */
    CurriculumResponse replaceKnowledgeGroups(String id, List<KnowledgeGroupDto> knowledgeGroups);

    /**
     * F10: Thêm mới một nhóm kiến thức vào CTĐT.
     */
    CurriculumResponse addKnowledgeGroup(String id, KnowledgeGroupDto dto);

    /**
     * F10: Cập nhật thông tin một nhóm kiến thức theo groupCode.
     */
    CurriculumResponse updateKnowledgeGroup(String id, String groupCode, UpdateKnowledgeGroupRequest request);

    /**
     * F10: Xóa một nhóm kiến thức (kiểm tra ràng buộc không có môn học trực thuộc).
     */
    CurriculumResponse deleteKnowledgeGroup(String id, String groupCode);

    /**
     * F16 & F17: Cấu hình chuyên ngành và thời điểm chọn định hướng (EARLY / LATER - BR18).
     */
    CurriculumResponse configureSpecializations(String id, ConfigureSpecializationsRequest request);

    /**
     * F16: Thêm mới một chuyên ngành / định hướng vào CTĐT.
     */
    CurriculumResponse addSpecialization(String id, SpecializationDto dto);

    /**
     * F16: Xóa một chuyên ngành / định hướng khỏi CTĐT.
     */
    CurriculumResponse deleteSpecialization(String id, String specCode);

    /**
     * F11–F14: Gán học phần vào học kỳ của CTĐT với loại môn, nhóm KT, định hướng và tiên quyết.
     */
    CurriculumResponse addCourseToCurriculum(String id, AddCurriculumCourseRequest request);

    /**
     * F11–F14: Cập nhật cấu hình học phần trong CTĐT (đổi kỳ, đổi loại, đổi nhóm KT, đổi môn tiên quyết).
     */
    CurriculumResponse updateCourseInCurriculum(String id, String courseCode, UpdateCurriculumCourseRequest request);

    /**
     * F11: Xóa học phần khỏi CTĐT.
     */
    CurriculumResponse deleteCourseFromCurriculum(String id, String courseCode);
}
