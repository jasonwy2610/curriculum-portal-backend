# TỔNG HỢP YÊU CẦU CHỨC NĂNG & QUY TẮC NGHIỆP VỤ
## Hệ thống Quản lý Chương trình Đào tạo (CTĐT) - HUFLIT

---

## 1. Phạm vi & Vai trò

Hệ thống chỉ có **2 vai trò chính**:
- **Sinh viên (STUDENT / SV)**: Tra cứu CTĐT, xem kết quả học tập, nhận thông báo.
- **Giảng viên / Quản trị (ADMIN / GV)**: Quản lý CTĐT, danh mục học phần, ngành đào tạo, quy tắc thay thế, thông báo.

**Không có** quy trình duyệt nhiều cấp (multi-level approval workflow).

---

## 2. Bảng 1: Yêu cầu chức năng (F01 – F42)

### Xác thực & Phân quyền (Đã hoàn thành)
| Mã   | Chức năng                          | Vai trò    | Trạng thái  |
|------|------------------------------------|------------|-------------|
| F01  | Đăng nhập hệ thống                | SV, GV     | ✅ Done     |
| F02  | Phân quyền theo vai trò           | SV, GV     | ✅ Done     |
| F03  | Bảo mật JWT Stateless             | SV, GV     | ✅ Done     |

### Quản lý CTĐT (Curriculums)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F04  | Xem danh sách CTĐT                          | SV, GV     |
| F05  | Tạo mới CTĐT                                | GV         |
| F06  | Chỉnh sửa CTĐT                              | GV         |
| F07  | Clone CTĐT (Deep Clone)                     | GV         |
| F08  | Xem chi tiết CTĐT                           | SV, GV     |
| F09  | Quản lý phiên bản CTĐT cũ                   | GV         |

### Cấu trúc bên trong CTĐT
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F10  | Quản lý nhóm kiến thức                      | GV         |
| F11  | Quản lý học phần trong CTĐT                  | GV         |
| F12  | Phân loại bắt buộc / tự chọn                | GV         |
| F13  | Phân bổ học kỳ                               | GV         |
| F14  | Quản lý môn tiên quyết                      | GV         |

### Quản lý Ngành đào tạo (Majors)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F15  | CRUD ngành đào tạo                           | GV         |

### Quản lý Chuyên ngành / Định hướng
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F16  | Quản lý chuyên ngành / định hướng            | GV         |
| F17  | Cấu hình thời điểm chọn định hướng (EARLY / LATER) | GV  |

### Quản lý Danh mục Học phần (Courses)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F18  | CRUD danh mục học phần                       | GV         |
| F19  | Tìm kiếm học phần                            | SV, GV     |
| F20  | Xem chi tiết học phần                        | SV, GV     |

### Kết quả Học tập (Person 2)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F21  | Xem kết quả học tập cá nhân                 | SV         |
| F22  | Ghi nhận nhiều lần học cùng môn              | GV         |
| F23  | Hiển thị điểm 10 / chữ / hệ 4              | SV, GV     |
| F24  | Tính trạng thái đạt / chưa đạt              | SV, GV     |
| F25  | Lấy điểm cao nhất trong các lần học          | SV, GV     |

### Quy tắc Thay thế Học phần (Person 2)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F26  | Tạo quy tắc thay thế 1-1                    | GV         |
| F27  | Tạo quy tắc thay thế 1-N                    | GV         |
| F28  | Tạo quy tắc thay thế N-1                    | GV         |
| F29  | Quản lý danh sách quy tắc thay thế          | GV         |

### Thông báo In-app (Person 2)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F30  | Gửi thông báo khi thay đổi CTĐT             | GV (auto)  |
| F31  | Gửi thông báo khi tạo quy tắc thay thế     | GV (auto)  |
| F32  | Xem danh sách thông báo                     | SV         |
| F33  | Đánh dấu đã đọc / chưa đọc                 | SV         |

### Tra cứu CTĐT phía Sinh viên (Person 2)
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F34  | Xem CTĐT đang áp dụng                       | SV         |
| F35  | Xem tiến độ hoàn thành theo nhóm kiến thức  | SV         |
| F36  | Xem lộ trình học tập theo học kỳ             | SV         |
| F37  | Kiểm tra điều kiện tiên quyết               | SV         |
| F38  | Xem môn thay thế áp dụng cho mình           | SV         |

### Tìm kiếm & Lịch sử
| Mã   | Chức năng                                    | Vai trò    |
|------|----------------------------------------------|------------|
| F39  | Tìm kiếm CTĐT                               | SV, GV     |
| F40  | Xem chi tiết CTĐT từ kết quả tìm kiếm SV   | SV         |
| F41  | Lịch sử thay đổi CTĐT (changeLogs)          | GV         |
| F42  | Lịch sử thay đổi học phần (changeLogs)      | GV         |

---

## 3. Bảng 2: Quy tắc Nghiệp vụ (BR01 – BR18)

| Mã    | Quy tắc                                                                                                              |
|-------|-----------------------------------------------------------------------------------------------------------------------|
| BR01  | Mỗi sinh viên được gắn với một ngành (`majorId`) và khóa (`cohort`) để xác định CTĐT áp dụng.                       |
| BR02  | CTĐT của các khóa cũ KHÔNG BAO GIỜ bị xóa khi tạo CTĐT mới. Áp dụng Soft Delete / Versioning.                      |
| BR03  | Khi clone CTĐT, bản mới PHẢI hoàn toàn độc lập với bản gốc (Deep Clone toàn bộ cấu trúc).                           |
| BR04  | Một CTĐT có thể bao gồm nhiều nhóm kiến thức, học phần, và chuyên ngành / định hướng.                                |
| BR05  | Học phần trong CTĐT phải chỉ rõ: số tín chỉ, học kỳ dự kiến, loại (`MANDATORY`/`ELECTIVE`), và tiên quyết nếu có.   |
| BR06  | Một SV có thể học cùng một môn nhiều lần (nhiều lần attempt).                                                         |
| BR07  | Kết quả đánh giá lấy điểm cao nhất trong tất cả các lần học (highest score rule).                                     |
| BR08  | Quy tắc thay thế hỗ trợ 3 loại: 1-1, 1-N, N-1.                                                                      |
| BR09  | Quy tắc thay thế có phạm vi áp dụng theo `applicableCohorts[]`.                                                      |
| BR10  | KHÔNG tự động đăng ký hoặc chuyển đổi học phần thay thế cho sinh viên.                                                |
| BR11  | Điểm quy đổi 10 → chữ → hệ 4: Bảng quy đổi tích hợp khi code xử lý điểm.                                           |
| BR12  | Kết quả hiển thị theo CTĐT mà SV đang theo.                                                                           |
| BR13  | KHÔNG tự động tính GPA hoặc cảnh báo học vụ.                                                                           |
| BR14  | **CHỈ Giảng viên (`ROLE_ADMIN`)** mới được phép thay đổi dữ liệu CTĐT và cấu hình học phần (`POST`, `PUT`, `DELETE`). |
| BR15  | **Sinh viên (`ROLE_STUDENT`)** chỉ có quyền xem / tìm kiếm CTĐT, kết quả học tập, và thông tin liên quan (`GET`).    |
| BR16  | KHÔNG xây dựng quy trình duyệt nhiều cấp (multi-level approval).                                                      |
| BR17  | Thông báo thay đổi CTĐT lưu trạng thái đã đọc / chưa đọc (`readBy[]`).                                               |
| BR18  | Phải hỗ trợ CTĐT với chuyên ngành chọn từ đầu (`EARLY`) hoặc chọn ở giai đoạn sau (`LATER`).                          |

---

## 4. Ghi chú quan trọng

- `courseCode` (String) là **business key bất biến** xuyên suốt hệ thống.
- Ưu tiên **Soft Delete**: chuyển `status` sang `INACTIVE` hoặc `ARCHIVED` thay vì xóa cứng.
- Collection `courseOfferings` đã bị **loại bỏ hoàn toàn** khỏi phạm vi MVP.
- Hệ thống sử dụng đúng **8 collections**: `users`, `majors`, `courses`, `curriculums`, `replacementRules`, `academicRecords`, `notifications`, `changeLogs`.
