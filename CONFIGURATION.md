# Cấu hình chạy local

Không lưu URI MongoDB Atlas hoặc JWT secret trực tiếp trong Git.

PowerShell (chỉ áp dụng cho cửa sổ hiện tại):
```powershell
$env:MONGODB_URI = "mongodb+srv://<user>:<password>@<cluster>/<database>?retryWrites=true&w=majority"
$env:JWT_SECRET = "<base64-secret-at-least-32-bytes>"
$env:JWT_EXPIRATION_MS = "86400000"
.\mvnw.cmd spring-boot:run
```

Nếu URI hoặc mật khẩu Atlas từng được commit/chia sẻ, hãy đổi mật khẩu database user và cập nhật URI mới. Dùng một JWT secret riêng cho môi trường local.
