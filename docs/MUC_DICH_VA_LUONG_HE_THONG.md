# TỔNG QUAN MỤC ĐÍCH VÀ LUỒNG HOẠT ĐỘNG CỦA REPO

## 1) Repo này dùng để làm gì?

Repo `android_station_image_processing` là **trạm thị giác và điều khiển chạy trực tiếp trên điện thoại Android** cho xe mecanum mini tự hành.

Thay vì dùng laptop để nhận camera, chạy AI rồi gửi lệnh xuống xe, repo này đưa toàn bộ phần:

- nhận ảnh từ camera điện thoại,
- chạy nhận diện vật cản,
- nhận diện marker đích ArUco,
- ra quyết định điều hướng,
- gửi lệnh điều khiển xuống ESP32-S3,
- và cung cấp giao diện giám sát/điều khiển qua web,

vào **một ứng dụng Android duy nhất**.

Nói ngắn gọn: **điện thoại gắn trên xe chính là “bộ não thị giác” của hệ thống**.

---

## 2) Repo này phục vụ cho điều gì?

Repo phục vụ cho các nhu cầu sau:

### a. Điều khiển xe tự hành theo thị giác
Ứng dụng dùng camera sau của điện thoại để quan sát phía trước, phát hiện:

- vật cản,
- marker ArUco mục tiêu,
- vị trí lệch trái/phải của mục tiêu,
- và quyết định khi nào tiến, lùi, xoay, hoặc dừng.

### b. Thay thế trạm AI dùng laptop
Kiến trúc cũ thường có nhiều bước trung gian:

`camera -> truyền ảnh -> laptop -> AI -> server lệnh -> xe`

Kiến trúc mới của repo này rút ngắn thành:

`camera điện thoại -> AI trên điện thoại -> lệnh UDP -> ESP32-S3`

Điều này giúp hệ thống gọn hơn, ít phụ thuộc thiết bị ngoài hơn.

### c. Làm giao diện vận hành tại chỗ
Repo không chỉ xử lý AI mà còn cung cấp:

- giao diện trực tiếp trên màn hình điện thoại,
- công tắc bật/tắt Auto,
- trang web dashboard để giám sát và điều khiển từ thiết bị khác cùng mạng.

### d. Làm cầu nối tích hợp với firmware ESP32-S3
Repo này định nghĩa phần **Android-side contract** để firmware bên ESP32 hiểu và thực thi đúng các lệnh điều khiển như:

- `F`, `B`, `Q`, `E`, `S`
- và các lệnh mở rộng như `L`, `R`, `G`, `H`, `J`, `K`

---

## 3) Lợi ích chính của kiến trúc này

### a. Giảm độ trễ
Ảnh không cần gửi sang laptop mới xử lý. Điều này giúp phản ứng tránh vật cản và bám marker nhanh hơn.

### b. Giảm rủi ro rớt FPS do nhiều tầng trung gian
Ít bước truyền dữ liệu hơn đồng nghĩa với ít điểm nghẽn hơn.

### c. Hệ thống gọn và cơ động hơn
Chỉ cần:

- xe,
- điện thoại Android,
- ESP32-S3,
- và thiết bị phụ để mở dashboard web nếu muốn.

Không bắt buộc phải có laptop trong vòng lặp điều khiển thời gian thực.

### d. Dễ kiểm thử tại hiện trường
Người vận hành có thể:

- xem trạng thái ngay trên điện thoại,
- bật/tắt Auto trên điện thoại,
- hoặc mở dashboard web để quan sát video và điều khiển tay.

### e. Tách rõ trách nhiệm giữa Android và firmware
- **Android app**: nhìn, suy luận, quyết định lệnh mức cao.
- **ESP32-S3 firmware**: nhận lệnh, điều khiển motor, đảm bảo failsafe phần cứng.

---

## 4) Mục tiêu vận hành thực tế của hệ thống

Hệ thống hiện tại hướng đến bài toán:

1. Điện thoại quan sát phía trước bằng camera sau.
2. Nếu thấy vật cản gần -> tránh hoặc lùi.
3. Nếu thấy marker mục tiêu -> căn giữa marker rồi tiến tới.
4. Khi đạt điều kiện tới đích -> dừng.
5. Nếu mất marker -> dừng ngắn hoặc xoay tìm lại.

Ngoài chế độ Auto, hệ thống còn hỗ trợ:

- điều khiển tay từ dashboard web,
- dừng xe khẩn,
- đổi model AI,
- xem video trực tiếp từ chính pipeline AI của điện thoại.

---

## 5) Luồng hoạt động tổng quan

### Luồng tổng thể mức cao

```text
Camera điện thoại
-> Ứng dụng Android
-> YOLO + ArUco
-> Decision Engine
-> Lệnh điều khiển
-> UDP
-> ESP32-S3
-> Motor
```

### Luồng có thêm lớp giám sát web

```text
Camera điện thoại
-> Android app xử lý AI
-> Decision Engine / StationController
-> UDP command xuống xe
-> Dashboard web trên chính điện thoại
-> Thiết bị khác mở dashboard để giám sát / điều khiển
```

Điểm quan trọng:

- **web dashboard không phải là bộ não điều khiển chính**;
- dashboard chỉ là lớp vận hành, giám sát và điều khiển bổ sung;
- vòng lặp AI thời gian thực vẫn nằm trong app Android.

---

## 6) Luồng hoạt động chi tiết bên trong app

### Bước 1: CameraX lấy frame
Ứng dụng dùng `CameraX` để nhận frame từ camera sau.

### Bước 2: Chuyển frame sang `Bitmap`
Frame `ImageProxy` được xoay và chuyển sang `Bitmap` để các khối AI xử lý.

### Bước 3: YOLO phát hiện vật cản
Model TFLite YOLO chạy trên điện thoại để phát hiện bounding box của các vật thể được xem là chướng ngại trong vùng nhìn.

### Bước 4: OpenCV ArUco phát hiện marker mục tiêu
Khối ArUco detector tìm marker ID mục tiêu (hiện tại là ID `0`) để làm đích điều hướng.

### Bước 5: Decision Engine quyết định lệnh
Logic quyết định xét theo ưu tiên:

1. Nếu Auto đang tắt -> dừng.
2. Nếu có vật cản nguy hiểm -> tránh hoặc lùi.
3. Nếu có marker -> xoay/căn giữa/tiến.
4. Nếu mới mất marker -> dừng ngắn.
5. Nếu không thấy marker -> xoay tìm marker.

### Bước 6: StationController đồng bộ trạng thái chung
`StationController` giữ trạng thái dùng chung cho:

- app UI,
- web dashboard,
- Auto ON/OFF,
- manual override,
- lệnh hiện tại,
- trạng thái marker / FPS / lỗi UDP.

Nhờ đó, khi bật/tắt Auto ở app hoặc web thì cả hai phía đều cập nhật đồng bộ.

### Bước 7: UdpCommandSender gửi lệnh xuống xe
Lệnh được gửi đều đặn qua UDP đến ESP32-S3. Khi dừng Auto hoặc gặp tình huống an toàn, app ép gửi `S` để xe dừng.

### Bước 8: Web dashboard nhận status + video stream
App đồng thời host một web server cục bộ để:

- cung cấp dashboard HTML,
- cập nhật trạng thái realtime,
- nhận lệnh tay từ web,
- stream video JPEG qua WebSocket.

---

## 7) Luồng truyền dữ liệu

## 7.1 Luồng dữ liệu điều khiển tự hành

```text
Camera frame
-> Bitmap
-> YOLO detections + ArUco marker
-> DecisionEngine
-> command char
-> UdpCommandSender
-> UDP packet
-> ESP32-S3
-> motor action
```

Dữ liệu chính trong luồng này gồm:

- ảnh camera,
- danh sách bounding box vật cản,
- thông tin marker,
- lệnh điều khiển 1 ký tự.

---

## 7.2 Luồng dữ liệu giám sát UI điện thoại

```text
VisionResult + trạng thái controller
-> OverlayView
-> TextView trạng thái
-> Switch Auto
```

UI điện thoại hiển thị:

- trạng thái Auto,
- lệnh hiện tại,
- lý do ra quyết định,
- số vật cản,
- marker hiện tại,
- khoảng cách marker nếu có,
- FPS AI,
- lỗi UDP,
- địa chỉ dashboard web.

---

## 7.3 Luồng dữ liệu dashboard web

```text
StationController / VisionResult
-> WebControlServer
-> HTTP / WebSocket
-> trình duyệt laptop hoặc điện thoại khác
```

Dashboard web nhận 2 nhóm dữ liệu:

### a. Dữ liệu trạng thái
Gồm:

- Auto bật/tắt,
- command hiện tại,
- lý do,
- model hiện tại,
- FPS,
- lỗi UDP,
- thông tin marker,
- SSID Wi‑Fi.

### b. Dữ liệu video
Gồm:

- frame JPEG live,
- có thể bật/tắt overlay nhận diện,
- được gửi qua WebSocket `/ws`.

---

## 7.4 Luồng dữ liệu điều khiển tay từ web

```text
Người dùng giữ nút trên dashboard
-> WebSocket message
-> WebControlServer
-> StationController.pressManual(...)
-> tắt Auto
-> command override
-> UDP xuống ESP32-S3
```

Khi thả nút:

```text
Web release
-> StationController.releaseManual(...)
-> hết manual override
-> xe dừng / quay lại logic phù hợp
```

Ngoài ra có **deadman timeout**:

- nếu web mất kết nối,
- hoặc không còn tín hiệu giữ nút,
- manual command sẽ hết hạn,
- xe sẽ không tiếp tục chạy vô thời hạn.

---

## 8) Vai trò của từng thành phần chính

### `MainActivity`
Điểm điều phối chính của app:

- khởi tạo camera,
- khởi tạo AI,
- phân tích frame,
- cập nhật UI,
- gửi frame cho web stream,
- kết nối với `StationController` và `WebControlServer`.

### `DecisionEngine`
Chứa luật quyết định hướng đi dựa trên:

- vật cản,
- marker,
- trạng thái Auto.

### `StationController`
Giữ state điều khiển dùng chung và đảm bảo đồng bộ giữa:

- app,
- web,
- Auto,
- manual control.

### `UdpCommandSender`
Chịu trách nhiệm gửi command UDP lặp lại xuống ESP32-S3.

### `WebControlServer`
Phục vụ dashboard, API điều khiển và WebSocket stream/status.

### `FrameOverlayRenderer`
Vẽ khung nhận diện lên frame trước khi stream ra web nếu người dùng bật overlay.

---

## 9) Hệ thống này phù hợp trong bối cảnh nào?

Repo này phù hợp cho:

- demo xe tự hành mini,
- đồ án/đề tài AI + robotics,
- test nhanh ngoài hiện trường,
- nghiên cứu so sánh model YOLO trên thiết bị Android,
- tích hợp Android vision với ESP32-S3 qua giao thức đơn giản.

---

## 10) Tóm tắt ngắn gọn

Repo này là **trạm xử lý ảnh và điều khiển xe mecanum chạy trên Android**.

Nó giúp:

- nhận ảnh từ camera,
- phát hiện vật cản và marker,
- ra lệnh điều hướng,
- gửi lệnh tới ESP32-S3,
- đồng thời cho phép giám sát và điều khiển qua giao diện điện thoại và web dashboard.

Kiến trúc này giúp hệ thống:

- gọn hơn,
- trễ thấp hơn,
- dễ test hơn,
- và phù hợp cho vận hành thực tế của xe mini tự hành dùng điện thoại làm bộ não thị giác.