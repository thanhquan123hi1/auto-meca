# Hợp đồng tích hợp Android và ESP32-S3

## 1. Mục tiêu tài liệu
Tài liệu này chốt giao thức và trách nhiệm tích hợp giữa:
- firmware ESP32-S3 tại `E:\auto_meca`;
- ứng dụng Android tại `D:\GitHub\android_station_image_processing`.

Mục tiêu là để hai bên hiểu cùng một chuẩn điều khiển, tránh lệch nghĩa lệnh và giảm rủi ro mất an toàn khi test xe thực.

## 2. Phân chia trách nhiệm
### 2.1. Android sở hữu
Android là bên **ra quyết định điều hướng mức cao**:
- đọc camera;
- phát hiện vật cản bằng YOLO TFLite;
- phát hiện marker ArUco ID `0` bằng OpenCV;
- quyết định lệnh điều hướng tiếp theo;
- gửi lệnh UDP định kỳ đến ESP32-S3;
- hiển thị UI, web monitor, trạng thái lỗi và thông tin debug.

### 2.2. ESP32-S3 sở hữu
ESP32-S3 là bên **điều khiển chuyển động mức thấp**:
- phát Wi‑Fi AP cho điện thoại kết nối;
- nhận lệnh UDP;
- kiểm tra hợp lệ của lệnh;
- ánh xạ lệnh thành chuyển động bánh xe;
- điều khiển PWM và driver động cơ;
- thực thi failsafe, stop khẩn cấp và timeout an toàn.

## 3. Chuẩn kết nối hiện tại
- SSID: `Mecanum-Car`
- Mật khẩu: `12345678`
- Chế độ Wi‑Fi: ESP32-S3 chạy Access Point
- IP ESP32-S3: `192.168.4.1`
- Giao thức điều khiển: UDP
- Port UDP: `4210`

Điện thoại Android phải kết nối thủ công vào Wi‑Fi `Mecanum-Car` trước khi bật chế độ tự hành.

## 4. Định dạng gói lệnh
### 4.1. Payload
- Mỗi gói UDP hiện tại chứa **1 ký tự ASCII**.
- Android gửi lại lệnh theo chu kỳ `100 ms` khi autonomous đang bật.
- Khi dừng autonomous, Android gửi `S` nhiều lần rồi dừng vòng lặp gửi.

### 4.2. Tập lệnh hỗ trợ
Firmware đang hỗ trợ các lệnh sau:

| Lệnh | Ý nghĩa chuẩn | Mô tả chuyển động mong muốn |
|------|---------------|-----------------------------|
| `F` | Tiến | Xe đi về phía trước theo hướng camera sau đang nhìn |
| `B` | Lùi | Xe lùi ngược hướng camera |
| `Q` | Xoay trái | Xe quay trái tại chỗ |
| `E` | Xoay phải | Xe quay phải tại chỗ |
| `S` | Dừng | Dừng tất cả động cơ ngay lập tức |
| `L` | Tịnh tiến trái | Trượt ngang trái |
| `R` | Tịnh tiến phải | Trượt ngang phải |
| `G` | Chéo tiến trái | Di chuyển chéo tiến trái |
| `H` | Chéo tiến phải | Di chuyển chéo tiến phải |
| `J` | Chéo lùi trái | Di chuyển chéo lùi trái |
| `K` | Chéo lùi phải | Di chuyển chéo lùi phải |

### 4.3. Tập lệnh Android hiện dùng
`DecisionEngine` hiện chủ yếu phát các lệnh:
- `F`
- `B`
- `Q`
- `E`
- `S`

Các lệnh mở rộng `L/R/G/H/J/K` đã được `UdpCommandSender` cho phép, nhưng hiện chưa phải luồng quyết định chính.

## 5. Quy tắc an toàn bắt buộc
### 5.1. Quy tắc phía firmware
Firmware phải tuân thủ các quy tắc sau:
- lệnh không hợp lệ -> dừng (`S`);
- packet rỗng hoặc lỗi đọc -> dừng;
- không nhận lệnh hợp lệ quá `600 ms` -> dừng;
- khi boot xong -> mặc định ở trạng thái dừng;
- nếu có nghi ngờ về packet hoặc trạng thái xử lý -> ưu tiên dừng an toàn.

### 5.2. Quy tắc phía Android
Android phải tuân thủ các quy tắc sau:
- khi autonomous tắt -> lệnh bắt buộc là `S`;
- khi đổi model -> dừng autonomous, gửi `S`, rồi mới tải model mới;
- khi chưa có dữ liệu tin cậy hoặc có lỗi ảnh hưởng an toàn -> ưu tiên `S`;
- không gửi lệnh ngoài tập firmware hiểu được.

## 6. Các điểm cần xác minh thực địa
Dù code hiện tại đã thống nhất tên lệnh, khi test thật vẫn phải xác minh lại các semantics sau:
- `Q` trên xe thật có đúng là quay trái theo góc nhìn camera không;
- `E` có đúng là quay phải không;
- `F` có đúng là đi theo hướng camera sau đang nhìn không;
- `B` có đúng là lùi ngược hướng đó không;
- `S` có dừng ngay lập tức không;
- tần suất gửi `100 ms` và timeout `600 ms` có đủ an toàn và đủ mượt không.

Nếu semantics thực tế không đúng, phải sửa **mapping motor ở firmware** trước, không sửa cách hiểu lệnh ở Android theo kiểu tạm thời.

## 7. Hành vi hiện tại của DecisionEngine
Thứ tự ưu tiên điều khiển hiện tại:
1. autonomous tắt -> `S`;
2. có vật cản nguy hiểm -> tránh vật cản;
3. thấy marker ID `0` -> điều hướng về marker;
4. vừa mất marker -> dừng ngắn;
5. mất marker lâu hơn -> xoay tìm marker.

Điều này có nghĩa là trong phiên bản hiện tại:
- vật cản đang được ưu tiên hơn marker;
- xe có thể lùi khi vật cản quá gần;
- xe quay theo xung để giảm xoay liên tục;
- xe chưa có tốc độ theo nhiều mức, chỉ mới có khác biệt về mẫu lệnh theo thời gian.

## 8. Hướng mở rộng tương lai
Để giữ tương thích ngược, giai đoạn đầu vẫn nên dùng giao thức 1 ký tự. Các mở rộng tương lai có thể bao gồm:
- command + speed profile;
- command + mức PWM;
- packet có checksum hoặc sequence để debug;
- phản hồi trạng thái từ ESP32 về Android/web.

Các mở rộng này chỉ nên thực hiện sau khi giao thức hiện tại đã được test ổn định ngoài thực địa.

## 9. Checklist đồng bộ tối thiểu trước khi cho xe chạy trên sàn
- ESP32 phát đúng Wi‑Fi `Mecanum-Car`.
- Điện thoại Android kết nối được vào AP.
- Android gửi UDP đến `192.168.4.1:4210`.
- Firmware log ra lệnh nhận được.
- `F/B/Q/E/S` trên firmware khớp đúng ý nghĩa của Android.
- `S` dừng ngay.
- Timeout `600 ms` hoạt động đúng.
- Đổi model luôn dừng xe trước.
- Khi tắt app hoặc mất Wi‑Fi, xe tự dừng.
