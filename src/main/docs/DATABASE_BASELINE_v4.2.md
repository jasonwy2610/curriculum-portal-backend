# THIẾT KẾ CƠ SỞ DỮ LIỆU – BASELINE CHÍNH THỨC v4.2
Hệ thống Quản lý Chương trình Đào tạo (CTĐT) - HUFLIT
Thay thế Database_Design_v4.1

## 1. Trạng thái chốt thiết kế
- Hệ thống sử dụng đúng 8 collections: users, majors, courses, curriculums, replacementRules, academicRecords, notifications, changeLogs.
- Loại bỏ hoàn toàn collection courseOfferings khỏi MVP.
- courseCode là business key xuyên suốt hệ thống.
- Giữ specializationCodes: [] trong từng môn thuộc CTĐT.
- Một sinh viên có thể có nhiều lần học cùng một học phần; kết quả hiển thị/đánh giá lấy điểm cao nhất.
- Bảng quy đổi điểm 10 → điểm chữ → điểm hệ 4 CHƯA chốt trong database. Sẽ tích hợp khi bước code xử lý điểm được triển khai.

## 2. Các quyết định kỹ thuật chính thức
- Actor quản trị: role = ADMIN (Quản trị CTĐT, không mặc định là giảng viên).
- Users & Students: Gộp chung collection users.
- Majors: Collection riêng majors (danh mục dùng chung cho users và curriculums).
- Cấu trúc CTĐT: semesters[].courses[] (Phù hợp F34/F36 và hiển thị theo học kỳ).
- Business key: courseCode (Dùng thống nhất khi tham chiếu học phần trong CTĐT, tiên quyết, thay thế và điểm).
- Chuyên ngành trên môn: specializationCodes: [String].
- Môn tiên quyết: prerequisiteCourseCodes: [String] nằm trong course entry của CTĐT.
- Quy tắc thay thế: 1-1, 1-N, N-1 + applicableCohorts[].
- CourseOfferings: Loại bỏ khỏi MVP.
- AcademicRecords: 1 document/(student, course, curriculum) + attempts[] + cache (highestScore10, highestScore4, highestLetterGrade, status PASSED/FAILED).
- Notifications: recipientIds[] + readBy[] + replacementRuleId.
- ChangeLogs: changes[{field, oldValue, newValue}].
- Curriculum version: version + status + clonedFrom.
- Curriculum áp dụng: users.curriculumId.

## 3. Quy tắc toàn vẹn dữ liệu
- Spring Boot kiểm tra sự tồn tại của majorId, courseCode, curriculumId tại tầng Service trước khi ghi dữ liệu.
- Ưu tiên xóa mềm: chuyển status sang INACTIVE hoặc ARCHIVED thay vì xóa cứng.
- Clone CTĐT phải deep-copy toàn bộ cấu trúc knowledgeGroups, semesters, courses; clonedFrom chỉ lưu nguồn clone.
- Không tự động đăng ký hoặc chuyển học phần thay thế cho sinh viên.

## 4. Chi tiết 8 Collections
### 4.1 users
{
  "_id": ObjectId,
  "email": String,
  "passwordHash": String,
  "fullName": String,
  "role": "STUDENT" | "ADMIN",
  "status": "ACTIVE" | "INACTIVE",
  "studentCode": String | null,
  "majorId": ObjectId | null,
  "cohort": String | null,
  "curriculumId": ObjectId | null,
  "chosenSpecializationCode": String | null,
  "createdAt": Date,
  "updatedAt": Date
}

### 4.2 majors
{
  "_id": ObjectId,
  "code": String,
  "name": String,
  "description": String | null,
  "status": "ACTIVE" | "INACTIVE",
  "createdAt": Date,
  "updatedAt": Date
}

### 4.3 courses
{
  "_id": ObjectId,
  "courseCode": String,
  "courseName": String,
  "credits": Number,
  "theoryHours": Number,
  "practiceHours": Number,
  "description": String | null,
  "department": String | null,
  "status": "ACTIVE" | "INACTIVE" | "ARCHIVED",
  "createdAt": Date,
  "updatedAt": Date
}

### 4.4 curriculums
{
  "_id": ObjectId,
  "curriculumCode": String,
  "curriculumName": String,
  "majorId": ObjectId,
  "cohort": String,
  "appliedYear": Number,
  "totalCredits": Number,
  "status": "DRAFT" | "ACTIVE" | "ARCHIVED",
  "version": Number,
  "clonedFrom": ObjectId | null,
  "specializationTrack": "EARLY" | "LATER",
  "specializationDecisionSemester": Number | null,
  "specializations": [
    { "code": String, "name": String }
  ],
  "knowledgeGroups": [
    { "groupCode": String, "groupName": String, "order": Number }
  ],
  "semesters": [
    {
      "semesterNumber": Number,
      "courses": [
        {
          "courseCode": String,
          "type": "MANDATORY" | "ELECTIVE",
          "knowledgeGroupCode": String,
          "specializationCodes": [String],
          "prerequisiteCourseCodes": [String]
        }
      ]
    }
  ],
  "createdBy": ObjectId,
  "createdAt": Date,
  "updatedAt": Date
}

### 4.5 replacementRules
{
  "_id": ObjectId,
  "ruleType": "1-1" | "1-N" | "N-1",
  "oldCourseCodes": [String],
  "newCourseCodes": [String],
  "majorId": ObjectId,
  "applicableCohorts": [String],
  "effectiveDate": Date,
  "reason": String,
  "status": "ACTIVE" | "INACTIVE",
  "createdBy": ObjectId,
  "createdAt": Date,
  "updatedAt": Date
}

### 4.6 academicRecords
{
  "_id": ObjectId,
  "studentId": ObjectId,
  "courseCode": String,
  "curriculumId": ObjectId,
  "attempts": [
    {
      "semesterCode": String,
      "score10": Number,
      "letterGrade": String | null,
      "score4": Number | null,
      "attemptNumber": Number
    }
  ],
  "highestScore10": Number | null,
  "highestLetterGrade": String | null,
  "highestScore4": Number | null,
  "status": "PASSED" | "FAILED",
  "updatedAt": Date
}

### 4.7 notifications
{
  "_id": ObjectId,
  "type": "COURSE_REPLACEMENT",
  "title": String,
  "message": String,
  "curriculumId": ObjectId | null,
  "courseCode": String | null,
  "replacementRuleId": ObjectId | null,
  "recipientIds": [ObjectId],
  "readBy": [ObjectId],
  "createdBy": ObjectId,
  "createdAt": Date
}

### 4.8 changeLogs
{
  "_id": ObjectId,
  "entityType": "Curriculum" | "Course",
  "entityId": ObjectId,
  "action": "CREATE" | "UPDATE" | "DELETE" | "CLONE",
  "changedBy": ObjectId,
  "changes": [
    {
      "field": String,
      "oldValue": Any,
      "newValue": Any
    }
  ],
  "createdAt": Date
}

## 5. Indexes chính thức
- users: unique(email); unique(studentCode) partial filter; index(majorId); index(curriculumId)
- majors: unique(code)
- courses: unique(courseCode); text(courseName)
- curriculums: unique(curriculumCode); index(majorId); index(cohort); index(majorId, cohort, version)
- replacementRules: multikey(oldCourseCodes); multikey(newCourseCodes); index(applicableCohorts); index(majorId, applicableCohorts)
- academicRecords: unique(studentId, courseCode, curriculumId); index(studentId); index(curriculumId)
- notifications: index(recipientIds); index(curriculumId); index(createdAt); index(replacementRuleId)
- changeLogs: index(entityType, entityId, createdAt)