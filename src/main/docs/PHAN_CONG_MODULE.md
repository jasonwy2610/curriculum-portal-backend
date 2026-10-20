# PHÂN CÔNG MODULE & KIẾN TRÚC BACKEND — CHAPTER A
## Hệ thống Quản lý CTĐT - HUFLIT (Person 1 — Module A)

---

## 1. Nguyên tắc phân chia

- F01–F03: Đã hoàn thành (Auth/JWT).
- Mỗi người làm trọn vẹn cả Backend + Frontend của Module mình, không chia ngang BE/FE.
- Hạn chế tối đa conflict code.

---

## 2. NGƯỜI 1 — MODULE A: Quản lý CTĐT + Danh mục học phần + Lịch sử

Phụ trách **23 Functions**: F04–F20, F39, F41, F42.

Collections chính do Người 1 sở hữu: `curriculums`, `majors`, `courses`, `changeLogs`.

Git Branch Người 1: `feature/curriculum-management`

### Thứ tự code:
- **Phase A1**: F15 (Major) → F18, F19, F20 (Course)
- **Phase A2**: F04, F05, F08, F06, F07, F09 (Curriculum Core & Clone)
- **Phase A3**: F10, F11, F12, F13, F14, F16, F17 (Cấu trúc bên trong CTĐT)
- **Phase A4**: F39, F41, F42 (Search & Change History)

---

## 3. NGƯỜI 2 — MODULE B: Sinh viên + Học tập + Môn thay thế + Thông báo

Phụ trách **19 Functions**: F21–F38, F40.

Collections chính Người 2: `academicRecords`, `replacementRules`, `notifications`.

Git Branch Người 2: `feature/student-academic`

---

## A.1 API Ownership chính thức — Person 1

| Mã Chức năng | HTTP Method    | Endpoint                              | Mô tả                                          |
|-------------|----------------|---------------------------------------|------------------------------------------------|
| F15         | GET            | `/api/majors`                         | Danh sách + tìm kiếm ngành đào tạo            |
| F15         | GET            | `/api/majors/{id}`                    | Chi tiết ngành theo ID                         |
| F15         | GET            | `/api/majors/code/{code}`             | Chi tiết ngành theo mã ngành                   |
| F15         | POST           | `/api/majors`                         | Tạo mới ngành (ADMIN)                          |
| F15         | PUT            | `/api/majors/{id}`                    | Cập nhật ngành (ADMIN)                         |
| F15         | DELETE         | `/api/majors/{id}`                    | Xóa mềm ngành (ADMIN)                         |
| F18         | POST           | `/api/courses`                        | Tạo mới học phần (ADMIN)                       |
| F18         | PUT            | `/api/courses/{id}`                   | Cập nhật học phần (ADMIN)                       |
| F18         | DELETE         | `/api/courses/{id}`                   | Xóa mềm học phần (ADMIN)                       |
| F19         | GET            | `/api/courses`                        | Tìm kiếm học phần (?keyword=&department=&status=) |
| F19         | GET            | `/api/courses?keyword=`               | Tìm kiếm học phần theo từ khóa                |
| F20         | GET            | `/api/courses/{id}`                   | Chi tiết học phần theo ID                      |
| F20         | GET            | `/api/courses/code/{courseCode}`       | Chi tiết học phần theo mã                      |
| F04         | GET            | `/api/curriculums`                    | Danh sách CTĐT                                 |
| F05         | POST           | `/api/curriculums`                    | Tạo mới CTĐT (ADMIN)                           |
| F06         | PUT            | `/api/curriculums/{id}`               | Chỉnh sửa CTĐT (ADMIN)                         |
| F07         | POST           | `/api/curriculums/{id}/clone`         | Clone CTĐT - Deep Clone (ADMIN)                 |
| F08         | GET            | `/api/curriculums/{id}`               | Chi tiết CTĐT                                  |
| F09–F14     | —              | Tích hợp trong `/api/curriculums` APIs | Phiên bản, nhóm KT, học phần, học kỳ, tiên quyết |
| F16–F17     | —              | Tích hợp trong `/api/curriculums` APIs | Chuyên ngành, thời điểm chọn EARLY/LATER       |
| F39         | GET            | `/api/curriculums?keyword=`           | Tìm kiếm CTĐT                                 |
| F41         | GET            | `/api/change-logs/curriculum/{id}`    | Lịch sử thay đổi CTĐT                         |
| F42         | GET            | `/api/change-logs/course/{id}`        | Lịch sử thay đổi học phần                     |

---

## A.2 Cấu trúc Package-by-Feature bắt buộc

```text
vn.edu.huflit.portal/
├── PortalApplication.java
├── config/                    # SecurityConfig, JWT, GlobalExceptionHandler
├── dto/response/              # ApiResponse (shared), LoginResponse, LoginRequest
├── entity/                    # User (shared), Person 2 entities
├── repository/                # UserRepository (shared), Person 2 repositories
├── service/                   # AuthService (shared)
│
├── major/                     # === PERSON 1: F15 ===
│   ├── controller/
│   │   └── MajorController.java
│   ├── service/
│   │   ├── MajorService.java
│   │   └── MajorServiceImpl.java
│   ├── repository/
│   │   └── MajorRepository.java
│   ├── dto/
│   │   ├── CreateMajorRequest.java
│   │   ├── UpdateMajorRequest.java
│   │   └── MajorResponse.java
│   └── model/
│       └── Major.java
│
├── course/                    # === PERSON 1: F18, F19, F20 ===
│   ├── controller/
│   │   └── CourseController.java
│   ├── service/
│   │   ├── CourseService.java
│   │   └── CourseServiceImpl.java
│   ├── repository/
│   │   └── CourseRepository.java
│   ├── dto/
│   │   ├── CreateCourseRequest.java
│   │   ├── UpdateCourseRequest.java
│   │   └── CourseResponse.java
│   └── model/
│       └── Course.java
│
├── curriculum/                # === PERSON 1: F04–F14, F16–F17, F39 ===
│   ├── controller/
│   ├── service/
│   ├── repository/
│   │   └── CurriculumRepository.java
│   ├── dto/
│   └── model/
│       └── Curriculum.java
│
└── changelog/                 # === PERSON 1: F41, F42 ===
    ├── controller/
    ├── service/
    ├── repository/
    │   └── ChangeLogRepository.java
    └── model/
        └── ChangeLog.java
```

---

## A.3 Quy tắc Phân quyền trên Controller (BR14 & BR15)

- **Endpoint GET** (`danh sách`, `chi tiết`, `tìm kiếm`): Cho phép cả `ADMIN` và `STUDENT` (chỉ cần `authenticated`).
- **Endpoint POST, PUT, DELETE** (`tạo`, `sửa`, `xóa`): Chỉ cho phép `ADMIN` — áp dụng `@PreAuthorize("hasRole('ADMIN')")` ở cấp phương thức.
- **Không** đặt `@PreAuthorize` ở cấp class (controller-level) để tránh chặn quyền xem của SV.

---

## A.4 Quy ước Đặt tên Bắt buộc

| Tên trường                  | Mô tả                                                   |
|-----------------------------|----------------------------------------------------------|
| `courseCode`                | Business key bất biến cho học phần (KHÔNG dùng `courseId`) |
| `curriculumId`              | ObjectId tham chiếu đến CTĐT                             |
| `majorId`                   | ObjectId tham chiếu đến ngành đào tạo                    |
| `studentId`                 | ObjectId tham chiếu đến sinh viên (trong academicRecords) |
| `specializationCodes`       | Mảng mã chuyên ngành áp dụng cho môn học                 |
| `prerequisiteCourseCodes`   | Mảng mã môn tiên quyết                                  |
| `replacementRuleId`         | ObjectId tham chiếu đến quy tắc thay thế                |
| `knowledgeGroupCode`        | Mã nhóm kiến thức                                        |