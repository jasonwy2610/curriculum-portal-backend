# HUFLIT Curriculum Portal - ReactJS

## Công nghệ
- React + Vite
- Fetch API, JWT Bearer
- Responsive dashboard cho STUDENT và ADMIN

## Chạy frontend
1. Cài Node.js LTS (Node 20+).
2. Mở terminal tại thư mục `frontend`.
3. Chạy `npm install`.
4. Sao chép `.env.example` thành `.env` nếu cần đổi địa chỉ backend. Mặc định API là `http://localhost:8080`.
5. Chạy `npm run dev` và mở URL Vite hiển thị (mặc định `http://localhost:5173`).

## Tài khoản seed
- Student: `25001234@huflit.edu.vn` / `123456`
- Admin: `admin@huflit.edu.vn` / `123456`

## Các màn hình
### STUDENT
- Tổng quan và tiến độ CTĐT
- CTĐT áp dụng, học phần theo học kỳ
- Kết quả học tập
- Quy tắc môn học thay thế
- Thông báo và đánh dấu đã đọc

### ADMIN
- Tổng quan quản trị
- Ghi nhận kết quả học tập cho sinh viên (studentId và curriculumId là MongoDB ObjectId)
- Tạo/cập nhật quy tắc thay thế
- Tạo thông báo

## Lưu ý tích hợp
- Frontend gọi API backend đang có ở `/api/v1/...`; backend cần được chạy ở port 8080.
- JWT chỉ được giữ trong localStorage để phục vụ demo môn học. Với môi trường production, cân nhắc chính sách lưu token chặt chẽ hơn.
- Một số form quản trị yêu cầu MongoDB ObjectId; đây là mã `_id` trong collection `users`, `curriculums`, `majors` tương ứng.
- Dữ liệu rỗng có thể xuất hiện nếu Atlas chưa seed dữ liệu cho collection tương ứng.
