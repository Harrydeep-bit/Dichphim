# Dịch Phim: dịch phim nước ngoài sang tiếng Việt ngay khi đang xem

App Android chạy song song với Chrome. App nghe âm thanh của phim đang phát và tự nhận ra thứ tiếng (Anh, Trung, Hàn, Nhật, Thái, Pháp...). Sau đó app dịch lời thoại sang tiếng Việt, **hiện phụ đề nổi** đè lên màn hình phim và **đọc to** bản dịch.

App chỉ lấy âm thanh, không quay hình. Việc dịch dùng Google Gemini (có gói miễn phí).

## Cách lấy file APK

1. Vào github.com → dấu **+** → **New repository** → đặt tên `DichPhim` → **Create repository**.
2. Bấm **uploading an existing file** → tải lên file `DichPhim.zip` → **Commit changes**.
3. Bấm **Add file → Create new file**, gõ tên: `.github/workflows/build.yml`, dán nội dung file `build.yml` vào → **Commit changes**.
4. Vào tab **Actions**, chờ 3–5 phút đến khi hiện dấu ✅.
5. Bấm vào lần chạy đó → mục **Artifacts** → tải `DichPhim-apk` (file zip có `DichPhim.apk` bên trong).
6. Giải nén, mở `DichPhim.apk` để cài (cho phép "Cài app không rõ nguồn gốc").

Các bản sau cài đè lên được, không cần gỡ app cũ.

## Lấy mã API Gemini (miễn phí, làm một lần)

1. Trong app bấm **Lấy mã API miễn phí**, hoặc vào aistudio.google.com/apikey.
2. Đăng nhập Gmail → **Create API key** → sao chép mã (bắt đầu bằng `AIza...`).
3. Dán vào ô **Mã API Gemini** trong app.

## Cách dùng

1. Mở Dịch Phim → **BẮT ĐẦU DỊCH**.
2. Cho phép Micro, Thông báo và **Hiển thị trên ứng dụng khác**.
3. Khi máy hỏi chia sẻ màn hình, chọn **Toàn bộ màn hình** → **Bắt đầu**.
4. Mở Chrome, vào web phim và phát. Phụ đề tiếng Việt hiện sau khoảng 2–4 giây.
5. Kéo dòng phụ đề lên/xuống để đổi chỗ, chạm nhẹ để ẩn tạm.
6. Dừng: bấm **DỪNG DỊCH** trong app, hoặc bấm **Dừng** trên thanh thông báo.

Có thể bật/tắt riêng phụ đề nổi và đọc to, chỉnh cỡ chữ và tốc độ đọc.

## Riêng máy Xiaomi / Redmi

- Cài đặt → Ứng dụng → Quản lý ứng dụng → **Dịch Phim** → **Quyền khác** → bật **Hiển thị cửa sổ bật lên khi chạy nền** và **Hiển thị trên ứng dụng khác**.
- **Tiết kiệm pin** → chọn **Không hạn chế**. Bật **Tự khởi chạy**.

## Khi gặp trục trặc

- **Không có giọng đọc tiếng Việt**: cài *Dịch vụ giọng nói của Google* từ CH Play, chọn làm bộ đọc mặc định, ngôn ngữ Tiếng Việt.
- **Không ra phụ đề**: một số web phim có bản quyền chặn thu âm thanh; video tắt tiếng thì app cũng không nghe được.
- **Báo "hết lượt"**: gói miễn phí giới hạn số lần dịch mỗi phút và mỗi ngày (xem ở aistudio.google.com/rate-limit). Chờ một lát rồi dùng tiếp.
- **Mô hình AI**: nút *Nhanh, nhiều lượt* (gemini-3.5-flash-lite) là mặc định. Nút *Dịch hay hơn* (gemini-3.6-flash) dịch hay hơn nhưng có thể chậm hơn và ít lượt miễn phí hơn.
