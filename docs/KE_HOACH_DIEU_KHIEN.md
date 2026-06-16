# Kế hoạch thiết kế lại logic điều khiển

## 1. Mục tiêu thiết kế lại
Mục tiêu của giai đoạn refactor tiếp theo là làm cho xe:
- chạy ổn định hơn;
- ít rung camera hơn;
- không đổi lệnh quá dày gây giật;
- vẫn giữ an toàn khi mất tín hiệu hoặc mất dữ liệu tin cậy;
- giữ tương thích với giao thức UDP 1 ký tự ở giai đoạn đầu.

## 2. Nguyên tắc thiết kế
- Ưu tiên an toàn hơn tốc độ.
- Ưu tiên ảnh ổn định hơn quãng đường ngắn nhất.
- Nếu độ tin cậy thấp, ưu tiên dừng hoặc chuyển trạng thái bảo thủ.
- Không mở rộng giao thức sớm nếu chưa test ổn định semantics hiện tại.

## 3. Hướng thay đổi phía Android
### 3.1. Làm mượt quyết định
Cần bổ sung cơ chế:
- hysteresis để tránh đổi hướng trái/phải liên tục ở ranh giới deadzone;
- debounce lệnh để không đổi command mỗi frame rất nhỏ;
- smoothing theo thời gian để giữ lệnh ổn định trong khoảng ngắn;
- trạng thái “ảnh không tin cậy” để ưu tiên `S`.

### 3.2. Điều khiển theo trạng thái
Thiết kế lại decision policy theo mức hành vi:
- **tiến chậm** khi marker đã gần giữa và còn xa vừa phải;
- **xoay ngắn** khi lệch ít;
- **xoay mạnh hơn** khi lệch nhiều;
- **lùi chậm** khi vật cản quá gần;
- **dừng** khi dữ liệu AI hoặc marker không đủ tin cậy.

### 3.3. Điều kiện dừng bảo thủ hơn
Cần xác định thêm điều kiện dừng khi:
- AI lỗi liên tục trong nhiều frame;
- FPS xuống quá thấp trong thời gian dài;
- mất marker và không chắc vùng xung quanh an toàn;
- vật cản quá lớn chiếm phần lớn khung hình.

## 4. Hướng thay đổi phía firmware
### 4.1. Chuẩn bị nhiều mức tốc độ
Firmware hiện mới có một tốc độ mặc định. Giai đoạn tiếp theo nên:
- định nghĩa nhiều mức tốc độ như chậm / vừa / nhanh;
- tách hướng chuyển động và hồ sơ tốc độ;
- vẫn giữ hàm xử lý lệnh cũ cho tương thích.

### 4.2. PWM ramp
Cần thêm cơ chế tăng giảm PWM theo bước nhỏ để:
- giảm giật khi chuyển từ dừng sang chạy;
- giảm rung khi đổi giữa tiến và xoay;
- bảo vệ cơ khí và tăng ổn định camera.

### 4.3. Giao thức mở rộng tương lai
Có thể chuẩn bị thiết kế cho giai đoạn sau:
- `command + speed tier`;
- `command + pwm value`;
- packet có thêm metadata debug.

Nhưng mặc định ở giai đoạn đầu vẫn giữ packet 1 ký tự.

## 5. Cách triển khai theo pha
### Pha A: Ổn định semantics hiện tại
- xác minh `F/B/Q/E/S` trên xe thật;
- sửa mapping firmware nếu sai;
- thống nhất tài liệu.

### Pha B: Ổn định quyết định Android
- thêm hysteresis;
- thêm debounce;
- thêm state machine rõ hơn cho marker và obstacle.

### Pha C: Giảm rung chuyển động
- thêm speed tiers ở firmware;
- thêm PWM ramp;
- cân chỉnh lại DecisionEngine theo tốc độ chậm hơn.

### Pha D: Mở rộng giao thức nếu thực sự cần
- chỉ mở rộng packet khi 3 pha đầu đã ổn định ngoài thực tế.

## 6. Giả định đang dùng để thiết kế
- xe hiện chưa có encoder hoặc cảm biến khoảng cách bổ sung;
- khoảng cách đến marker hiện suy ra chủ yếu từ diện tích marker trong ảnh;
- Android là nguồn quyết định chính cho điều hướng;
- ESP32 là tầng an toàn và điều khiển cơ cấu chấp hành;
- mô hình tối ưu là chạy chậm nhưng chắc, không ưu tiên tốc độ cao.
