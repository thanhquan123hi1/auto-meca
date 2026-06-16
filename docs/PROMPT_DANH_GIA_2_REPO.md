# Prompt đánh giá logic và tối ưu hai repo

Bạn là kỹ sư hệ thống nhúng + Android + computer vision. Hãy đọc kỹ và đối chiếu hai repo sau như một hệ thống duy nhất:

- Firmware ESP32-S3: `E:\auto_meca`
- Android xử lý ảnh: `D:\GitHub\android_station_image_processing`

## Bối cảnh hệ thống
Đây là xe Mecanum tự hành 4 bánh.
- Android dùng camera sau để quan sát môi trường.
- Android chạy YOLO TFLite để phát hiện vật cản.
- Android chạy OpenCV ArUco để phát hiện marker đích ID `0`.
- Android dùng `DecisionEngine` để quyết định lệnh điều khiển mức cao.
- Android gửi lệnh UDP đến ESP32-S3.
- ESP32-S3 nhận lệnh và điều khiển động cơ.

## Hợp đồng tích hợp hiện tại
- Wi‑Fi AP: `Mecanum-Car`
- Password: `12345678`
- ESP32 IP: `192.168.4.1`
- UDP port: `4210`
- Android gửi lệnh mỗi `100 ms`
- Firmware timeout hiện tại: `600 ms`
- Payload UDP: 1 ký tự ASCII

Firmware hỗ trợ:
- `F` tiến
- `B` lùi
- `Q` xoay trái
- `E` xoay phải
- `S` dừng
- `L/R/G/H/J/K` là lệnh mở rộng Mecanum

DecisionEngine Android hiện chủ yếu dùng:
- `F`, `B`, `Q`, `E`, `S`

## Mục tiêu đánh giá
Mục tiêu hệ thống là:
- đi đến marker ArUco ID `0`;
- tránh vật cản;
- ưu tiên an toàn;
- không chạy quá nhanh để camera không rung và không mất nét;
- UI/web phải hiển thị tiếng Việt UTF-8 chuẩn, không lỗi font.

## Việc bạn phải làm
Hãy phân tích thật kỹ cả hai repo và đưa ra đánh giá có thể dùng trực tiếp để triển khai refactor. Đừng chỉ mô tả chung chung. Hãy chỉ ra các vấn đề thực tế, mâu thuẫn logic, điểm dễ gây tai nạn, điểm không đồng bộ giữa hai phía, và cơ hội tối ưu.

### Bắt buộc đánh giá các nội dung sau
1. Tính đúng đắn của giao thức Android <-> ESP32.
2. Tính nhất quán semantics của các lệnh `F B Q E S` và lệnh mở rộng.
3. Độ an toàn của failsafe khi mất gói UDP, tắt app, đổi model, hoặc mất Wi‑Fi.
4. Tính hợp lý của `DecisionEngine` khi có đồng thời marker và vật cản.
5. Rủi ro điều khiển làm camera rung, mất nét hoặc làm AI ra quyết định kém ổn định.
6. Các nút thắt hiệu năng: FPS, inference, CameraX, OpenCV, gửi lệnh, UI update.
7. Lỗi tiếng Việt / encoding / hard-code text trong app và web monitor.
8. Mức độ “decision-complete” của thiết kế hiện tại: chỗ nào còn phải suy đoán, chỗ nào nên chốt tài liệu/hợp đồng rõ hơn.
9. Khả năng mở rộng lên nhiều mức tốc độ hoặc command smoothing mà vẫn giữ tương thích giao thức cũ.
10. Các đề xuất refactor để hai repo đồng bộ và hoạt động trơn tru hơn.

## Định dạng đầu ra bắt buộc
Hãy trả lời đúng theo 7 phần sau:

### 1. Lỗi logic nghiêm trọng
Liệt kê các lỗi có thể làm xe chạy sai hướng, không dừng đúng lúc, hoặc làm quyết định AI nguy hiểm.

### 2. Điểm không đồng bộ giữa hai repo
Nêu rõ repo nào hiểu gì, repo nào giả định gì, và chỗ nào chưa được chốt rõ bằng code hoặc tài liệu.

### 3. Rủi ro an toàn
Tập trung vào mất UDP, mất Wi‑Fi, lỗi model, packet sai, marker sai, vật cản nhiều đối tượng, đổi model lúc đang chạy.

### 4. Nút thắt hiệu năng
Phân tích các điểm có thể làm giảm FPS, tăng độ trễ điều khiển, tạo rung lệnh, hoặc làm app không ổn định lâu dài.

### 5. Thay đổi ưu tiên cao / trung bình / thấp
Chia đề xuất refactor thành 3 mức ưu tiên rõ ràng. Mỗi đề xuất phải nói lý do và repo bị ảnh hưởng.

### 6. Checklist test tích hợp
Tạo checklist test theo thứ tự từ an toàn nhất đến thực địa, gồm cả test khô và test trên sàn.

### 7. Patch strategy theo từng repo
Tách rõ:
- thay đổi đề xuất cho firmware ESP32-S3;
- thay đổi đề xuất cho Android app;
- thay đổi tài liệu/hợp đồng dùng chung.

## Yêu cầu chất lượng phân tích
- Không được trả lời ở mức chung chung.
- Phải nêu rõ file, module, hành vi, giả định và rủi ro.
- Nếu có suy luận, phải ghi rõ đó là suy luận.
- Nếu một điểm đã ổn, hãy nói là ổn và vì sao.
- Ưu tiên các thay đổi giúp xe chạy ổn định, dễ debug, và an toàn hơn.
