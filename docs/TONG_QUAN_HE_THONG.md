# Tổng quan hệ thống xe Mecanum tự hành

## 1. Mục đích của hệ thống
Hệ thống này được xây dựng để điều khiển một xe Mecanum 4 bánh tự hành. Mục tiêu chính là:
- tự tìm và đi đến marker ArUco ID `0`;
- tránh vật cản xuất hiện trên đường đi;
- giữ chuyển động ổn định, an toàn;
- không chạy quá nhanh để hạn chế rung camera và giảm mất nét khi xử lý ảnh.

Hệ thống hiện được chia thành 2 phần mềm tách biệt nhưng phụ thuộc chặt chẽ:
- **Firmware ESP32-S3** tại `E:\auto_meca`;
- **Ứng dụng Android xử lý ảnh** tại `D:\GitHub\android_station_image_processing`.

## 2. Vai trò của từng repo
### 2.1. Repo firmware ESP32-S3
Repo `E:\auto_meca` chịu trách nhiệm:
- tạo Wi‑Fi Access Point cho điện thoại kết nối;
- nhận lệnh UDP từ ứng dụng Android;
- ánh xạ lệnh thành chuyển động thực tế của 4 bánh Mecanum;
- điều khiển PWM cho driver động cơ;
- thực hiện failsafe để dừng xe khi mất lệnh hoặc lệnh không hợp lệ.

### 2.2. Repo Android xử lý ảnh
Repo `D:\GitHub\android_station_image_processing` chịu trách nhiệm:
- lấy ảnh từ camera sau của điện thoại bằng CameraX;
- chạy YOLO TFLite để phát hiện vật cản;
- chạy OpenCV ArUco để phát hiện marker đích ID `0`;
- dùng `DecisionEngine` để chọn lệnh điều khiển mức cao;
- gửi lệnh UDP đến ESP32-S3 theo chu kỳ lặp;
- cung cấp giao diện trên điện thoại và web dashboard để theo dõi trạng thái, xem video trực tiếp và điều khiển xe.

## 3. Cách xe hoạt động ở mức người dùng
1. Nạp firmware vào ESP32-S3.
2. ESP32-S3 phát Wi‑Fi `Mecanum-Car`.
3. Điện thoại Android kết nối vào Wi‑Fi này.
4. Mở ứng dụng Android trên điện thoại gắn trên xe.
5. Ứng dụng hiển thị camera, công tắc Tự động, trạng thái AI, lệnh hiện tại và địa chỉ web dashboard.
6. Khi bật chế độ tự hành, ứng dụng bắt đầu:
   - quan sát vật cản;
   - tìm marker ArUco ID `0`;
   - đưa ra lệnh điều khiển;
   - gửi lệnh liên tục cho ESP32-S3.
7. ESP32-S3 nhận lệnh và điều khiển động cơ để xe di chuyển.
8. Khi nhấn dừng, đổi model, mất kết nối hoặc quá timeout, xe phải dừng an toàn.

## 4. Luồng xử lý dữ liệu tổng thể
Luồng xử lý hiện tại của hệ thống:

```text
Camera sau của điện thoại
-> CameraX ImageAnalysis
-> chuyển ImageProxy sang Bitmap đã xoay đúng chiều
-> YOLO TFLite phát hiện vật cản
-> OpenCV ArUco phát hiện marker ID 0
-> DecisionEngine chọn lệnh F/B/Q/E/S
-> UdpCommandSender gửi UDP tới 192.168.4.1:4210 mỗi 100 ms
-> Firmware ESP32-S3 nhận 1 ký tự ASCII
-> motor_control.cpp ánh xạ lệnh sang 4 bánh Mecanum
-> PWM điều khiển driver động cơ
-> xe di chuyển hoặc dừng
```

## 5. Giao thức điều khiển hiện tại
### 5.1. Kết nối mạng
- SSID: `Mecanum-Car`
- Mật khẩu: `12345678`
- IP ESP32 AP: thường là `192.168.4.1`
- UDP port: `4210`

### 5.2. Định dạng lệnh
Payload hiện tại là **1 ký tự ASCII**.

Các lệnh firmware đang hỗ trợ:
- `F`: tiến
- `B`: lùi
- `Q`: xoay trái tại chỗ
- `E`: xoay phải tại chỗ
- `S`: dừng
- `L`: tịnh tiến trái
- `R`: tịnh tiến phải
- `G`: chéo tiến trái
- `H`: chéo tiến phải
- `J`: chéo lùi trái
- `K`: chéo lùi phải

Hiện tại `DecisionEngine` của Android chủ yếu phát các lệnh:
- `F`, `B`, `Q`, `E`, `S`

## 6. Logic hoạt động hiện tại của ứng dụng Android
Thứ tự ưu tiên ra quyết định hiện tại:
1. Nếu chế độ tự hành tắt: gửi `S`.
2. Nếu có vật cản lớn: ưu tiên tránh vật cản.
3. Nếu nhìn thấy marker ID `0`: điều hướng về marker.
4. Nếu vừa mất marker trong thời gian ngắn: dừng tạm.
5. Nếu mất marker lâu hơn: xoay tìm marker theo nhịp.

### 6.1. Tránh vật cản
- Chọn object lớn nhất từ YOLO làm vật cản chính.
- Nếu diện tích vật cản rất lớn: lùi (`B`).
- Nếu vật cản nằm bên phải khung hình: xoay trái (`Q`).
- Nếu vật cản nằm bên trái khung hình: xoay phải (`E`).

### 6.2. Bám marker đích
- Nếu marker lệch trái: xoay trái theo xung.
- Nếu marker lệch phải: xoay phải theo xung.
- Nếu marker gần giữa: tiến (`F`).
- Nếu marker đủ lớn trong ảnh: coi là tới đích và dừng (`S`).

## 7. Trạng thái an toàn và xử lý lỗi
### 7.1. Trên firmware ESP32-S3
- Lệnh không hợp lệ: dừng xe.
- Packet rỗng hoặc lỗi đọc: dừng xe.
- Không nhận lệnh quá `600 ms`: dừng xe.
- Khi khởi động: xe ở trạng thái dừng.

### 7.2. Trên ứng dụng Android
- Khi tắt autonomous: gửi `S` nhiều lần rồi ngừng gửi lệnh.
- Khi đổi model: dừng autonomous, gửi `S`, đóng model cũ, tải model mới.
- Khi lỗi UDP: trạng thái lỗi được lưu để hiển thị trên UI/web.
- Khi lỗi tải YOLO hoặc ArUco: ứng dụng hiển thị lỗi, nhưng cần rà soát thêm để tránh chạy trong trạng thái không tin cậy.

## 8. Những giới hạn hiện tại
Hệ thống hiện tại vẫn còn các giới hạn kỹ thuật cần lưu ý:
- Android và firmware mới đồng bộ ở mức lệnh 1 ký tự, chưa có mức tốc độ chi tiết.
- Firmware đang dùng một tốc độ mặc định cố định (`MOTOR_SPEED_DEFAULT = 200`).
- Ứng dụng Android chưa điều khiển tốc độ theo ngữ cảnh.
- Logic “object lớn nhất là vật cản chính” có thể sai trong cảnh nhiều vật.
- Marker chưa có ước lượng khoảng cách vật lý thực, mới dừng theo diện tích trong ảnh.
- UI Android và dashboard web đã được chuẩn hóa tiếng Việt UTF-8.
- Dashboard web hiện hỗ trợ trạng thái realtime, điều khiển Auto, điều khiển tay giữ-thả, chọn model, video stream và bật/tắt overlay nhận diện.

## 9. Workflow test tích hợp tổng thể
### 9.1. Test firmware độc lập
- Nạp firmware.
- Mở serial monitor.
- Gửi thủ công các lệnh UDP `F/B/Q/E/S`.
- Xác nhận đúng chiều quay và đúng semantics.

### 9.2. Test app độc lập
- Cấp quyền camera.
- Kiểm tra tải model YOLO.
- Kiểm tra detect marker ArUco ID `0`.
- Kiểm tra UI hiển thị lệnh, lý do, FPS, lỗi UDP.

### 9.3. Test tích hợp khô
- Nâng bánh xe khỏi mặt đất.
- Kết nối điện thoại với AP `Mecanum-Car`.
- Bật autonomous.
- Di chuyển marker sang trái/phải/giữa.
- Đối chiếu lệnh trên UI với log serial firmware.

### 9.4. Test chạy chậm trên sàn
- Cho xe chạy với môi trường ít vật cản.
- Kiểm tra khả năng tiến đến marker.
- Theo dõi rung camera và độ ổn định FPS.

### 9.5. Test có vật cản
- Đặt vật cản trước marker.
- Xác nhận xe ưu tiên tránh vật cản trước.
- Quan sát khả năng quay lại hướng đích sau khi tránh.

### 9.6. Test lỗi và an toàn
- Ngắt Wi‑Fi hoặc tắt app đột ngột.
- Kiểm tra firmware có dừng trong timeout không.
- Đổi model khi xe đang bật autonomous.
- Kiểm tra xe phải dừng trước khi model mới hoạt động.

## 10. Mục tiêu của giai đoạn tiếp theo
Sau khi dashboard web và UI Android đã được nâng cấp, giai đoạn tiếp theo nên tập trung vào:
- kiểm thử thực tế video stream trên thiết bị thật;
- thiết kế nhiều mức tốc độ để giảm rung camera;
- thêm cơ chế làm mượt lệnh điều khiển;
- chuẩn hóa log và trạng thái debug;
- rà soát lại semantics điều khiển giữa góc nhìn camera và chuyển động thực tế của xe;
- đo hiệu năng AI FPS và stream FPS khi chạy lâu.
