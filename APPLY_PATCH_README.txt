HUONG DAN AP DUNG PATCH UI - PORTAL HUFLIT

Patch nay KHONG phai project moi. Chi gom cac file da thay doi/bo sung so voi Portal_Huflit(7).zip.

Cach ap dung:
1. Sao luu project hien tai hoac commit/stash cac thay doi dang lam.
2. Giai nen ZIP nay vao thu muc goc cua project Portal_Huflit cu.
3. Chon ghi de (overwrite) cac file trung ten khi duoc hoi. Cac file nam dung theo duong dan tuong doi.
4. Mo lai frontend/backend va chay kiem tra.

Danh sach file trong patch:
- frontend/src/main.jsx
- frontend/src/style.css
- src/main/java/vn/edu/huflit/portal/exception/GlobalExceptionHandler.java
- UI_UPDATE_NOTES.md

Luu y:
- Khong xoa hay thay the toan bo project cu.
- Khong chua .git, node_modules, dist, target hoac cac file du lieu local.
- GlobalExceptionHandler.java la thay doi backend; neu ban chi muon ap dung giao dien, co the bo qua file nay, nhung phan chuan hoa loi tu backend se khong co hieu luc.
- Nen xem diff/commit truoc khi ghi de neu ban hoac thanh vien khac da sua cung cac file nay.
