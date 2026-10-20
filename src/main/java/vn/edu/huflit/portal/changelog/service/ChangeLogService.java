package vn.edu.huflit.portal.changelog.service;

import org.bson.types.ObjectId;
import vn.edu.huflit.portal.changelog.dto.ChangeLogResponse;
import vn.edu.huflit.portal.changelog.model.ChangeLog;

import java.util.List;

public interface ChangeLogService {

    /**
     * Ghi nhận một vết kiểm toán (audit log) vào MongoDB collection changeLogs.
     */
    ChangeLog recordChange(String entityType, ObjectId entityId, String action,
                           List<ChangeLog.FieldChangeItem> changes, ObjectId changedBy);

    /**
     * Tra cứu lịch sử thay đổi với các bộ lọc tùy chọn (entityType, entityId, action).
     * Sắp xếp theo createdAt giảm dần (mới nhất lên đầu).
     */
    List<ChangeLogResponse> getChangeLogs(String entityType, String entityId, String action);

    /**
     * F41: Lấy toàn bộ lịch sử thay đổi của một CTĐT cụ thể.
     */
    List<ChangeLogResponse> getCurriculumChangeLogs(String curriculumId);

    /**
     * F42: Lấy toàn bộ lịch sử thay đổi của một học phần cụ thể.
     */
    List<ChangeLogResponse> getCourseChangeLogs(String courseId);
}
