# CHIA FUNCTION THEO MODULE (F04 → F42)
Dựa theo baseline DB v4.2 + F01–F42 đã chốt.

## 1. Nguyên tắc phân chia
- F01–F03: Đã hoàn thành (Auth/JWT).
- Mỗi người làm trọn vẹn cả Backend + Frontend của Module mình, không chia ngang BE/FE.
- Hạn chế tối đa conflict code.

## 2. NGƯỜI 1 — MODULE A: Quản lý CTĐT + Danh mục học phần + Lịch sử
Phụ trách 23 Functions:
- F15: Quản lý ngành (majors)
- F18: Quản lý danh mục học phần (courses)
- F19: Tìm kiếm học phần
- F20: Xem chi tiết học phần
- F04: Xem danh sách CTĐT (curriculums)
- F05: Tạo CTĐT
- F06: Chỉnh sửa CTĐT
- F07: Clone CTĐT (Deep Clone)
- F08: Xem chi tiết CTĐT
- F09: Quản lý phiên bản CTĐT cũ
- F10: Quản lý nhóm kiến thức
- F11: Quản lý học phần trong CTĐT
- F12: Bắt buộc / tự chọn
- F13: Phân bổ học kỳ
- F14: Môn tiên quyết
- F16: Quản lý định hướng / chuyên ngành
- F17: Cấu hình thời điểm chọn định hướng (EARLY / LATER)
- F39: Tìm kiếm CTĐT
- F41: Lịch sử thay đổi CTĐT (changeLogs)
- F42: Lịch sử thay đổi học phần (changeLogs)

Collections chính do Người 1 sở hữu:
- curriculums, majors, courses, changeLogs

Git Branch Người 1: `feature/curriculum-management`

Thứ tự code của Người 1:
- Phase A1: F15 (Major) → F18, F19, F20 (Course)
- Phase A2: F04, F05, F08, F06, F07, F09 (Curriculum Core & Clone)
- Phase A3: F10, F11, F12, F13, F14, F16, F17 (Cấu trúc bên trong CTĐT)
- Phase A4: F39, F41, F42 (Search & Change History)

## 3. NGƯỜI 2 — MODULE B: Sinh viên + Học tập + Môn thay thế + Thông báo
Phụ trách 19 Functions:
- F21–F25: Kết quả học tập cá nhân, nhiều lần học, điểm 10/chữ/hệ 4 (academicRecords)
- F26–F29: Quản lý học phần thay thế 1-1, 1-N, N-1 (replacementRules)
- F30–F33: Thông báo in-app (notifications)
- F34–F38, F40: Tra cứu CTĐT phía sinh viên (/api/student/*)

Collections chính Người 2: academicRecords, replacementRules, notifications.
Git Branch Người 2: `feature/student-academic`