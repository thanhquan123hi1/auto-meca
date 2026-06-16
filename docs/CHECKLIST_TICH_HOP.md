# Checklist kiểm tra logic tích hợp end-to-end

## 1. Kiểm tra semantics lệnh
- [ ] `F` trên firmware thực sự làm xe tiến theo hướng camera sau đang nhìn.
- [ ] `B` thực sự làm xe lùi.
- [ ] `Q` thực sự làm xe xoay trái.
- [ ] `E` thực sự làm xe xoay phải.
- [ ] `S` dừng tất cả động cơ ngay.

## 2. Kiểm tra mạng và giao thức
- [ ] ESP32 phát đúng SSID `Mecanum-Car`.
- [ ] Điện thoại kết nối được vào AP của ESP32.
- [ ] Android gửi UDP đúng IP `192.168.4.1` và port `4210`.
- [ ] Firmware log đúng lệnh nhận được.
- [ ] Packet không hợp lệ bị dừng an toàn.

## 3. Kiểm tra DecisionEngine hiện tại
- [ ] autonomous tắt thì luôn ra `S`.
- [ ] vật cản rất gần thì ra `B`.
- [ ] vật cản bên phải thì ra `Q`.
- [ ] vật cản bên trái thì ra `E`.
- [ ] marker lệch trái thì xe xoay trái theo xung.
- [ ] marker lệch phải thì xe xoay phải theo xung.
- [ ] marker gần giữa thì ra `F`.
- [ ] marker đủ lớn thì ra `S`.
- [ ] mất marker ngắn hạn thì dừng tạm.
- [ ] mất marker dài hơn thì xoay tìm marker.

## 4. Kiểm tra mâu thuẫn logic
- [ ] Khi đồng thời có vật cản và marker, hệ thống ưu tiên vật cản như thiết kế.
- [ ] Logic “object lớn nhất là vật cản chính” không gây hành vi sai quá nguy hiểm trong cảnh test.
- [ ] Lệnh hiển thị trên UI khớp với hành vi thực tế của xe.
- [ ] Reason hiển thị đủ rõ để giải thích vì sao xe đang chạy như vậy.

## 5. Kiểm tra an toàn
- [ ] Tắt autonomous làm app gửi `S` nhiều lần.
- [ ] Tắt app đột ngột làm xe dừng trong timeout firmware.
- [ ] Mất Wi‑Fi làm xe dừng trong timeout firmware.
- [ ] Đổi model khi đang chạy luôn khiến xe dừng trước.
- [ ] Lỗi YOLO hoặc ArUco không khiến xe tiếp tục chạy không kiểm soát.

## 6. Kiểm tra hiệu năng và độ ổn định
- [ ] FPS đủ ổn định trong điều kiện ánh sáng thật.
- [ ] Xe không chạy quá nhanh gây rung ảnh nặng.
- [ ] Lệnh không đổi trái/phải liên tục quá mức ở ranh giới marker.
- [ ] Web monitor cập nhật được command, reason, model, FPS, lỗi UDP.

## 7. Trình tự test khuyến nghị
1. [ ] Test firmware bằng gửi UDP thủ công.
2. [ ] Test app riêng với camera và marker.
3. [ ] Test tích hợp khi bánh xe được nâng khỏi mặt đất.
4. [ ] Test trên sàn với tốc độ thấp và không có vật cản.
5. [ ] Test trên sàn với marker + vật cản.
6. [ ] Test lỗi: mất mạng, đổi model, đóng app.
