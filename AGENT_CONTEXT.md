# AGENT_CONTEXT.md

## M?c ti?u file n?y
File n?y l? context c? ??nh ?? agent l?n sau **kh?ng c?n ??c l?i to?n b? project** m?i c? th? l?m vi?c.
?u ti?n c?p nh?t file n?y khi c? thay ??i ki?n tr?c, giao th?c, lu?ng ?i?u khi?n, UI ho?c quy ??c test.

---

## 1. T?ng quan h? th?ng
H? th?ng g?m 2 ph?n ch?nh:

### A. Android app
- Repo: `D:\GitHub\android_station_image_processing`
- Vai tr?:
  - CameraX l?y ?nh
  - YOLO TFLite ph?t hi?n v?t c?n
  - ArUco ph?t hi?n marker ??ch
  - DecisionEngine quy?t ??nh l?nh xe
  - UDP g?i l?nh qua ESP32
  - Host web dashboard tr?n c?ng `8088`
  - Stream video l?n web b?ng **WebRTC**

### B. ESP32 firmware
- Repo kh?c: `E:\auto_meca`
- Vai tr?:
  - Nh?n l?nh UDP t? Android
  - ?i?u khi?n xe mecanum
  - G?i telemetry kho?ng c?ch ??u xe / guard v? Android

---

## 2. Ki?n tr?c ?i?u khi?n hi?n t?i

### Lu?ng ?i?u khi?n xe
1. Camera frame v?o `MainActivity.analyzeFrame()`
2. YOLO detect v?t c?n
3. ArUco detect marker
4. `DecisionEngine` sinh command n?u ?ang Auto
5. `StationController` ?u ti?n manual command n?u web ?ang gi? n?t
6. `UdpCommandSender` g?i command sang ESP32

### Lu?ng ?i?u khi?n web
- Dashboard web k?t n?i WebSocket t?i Android server
- Web g?i:
  - `manual_press`
  - `manual_release`
  - signaling WebRTC: `webrtc_offer`, `webrtc_answer`, `webrtc_ice`
- HTTP endpoint d?ng cho:
  - `/api/auto/on`
  - `/api/auto/off`
  - `/api/stop`
  - `/api/model`

### Lu?ng video web
- ?? b? ho?n to?n WebSocket JPEG c?
- Hi?n d?ng:
  - signaling qua WebSocket n?i b? dashboard
  - media qua **WebRTC**
- Android publish frame b?ng `WebRtcStreamer.pushFrame(bitmap)`

---

## 3. File quan tr?ng nh?t

### Android control / AI
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\MainActivity.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\control\StationController.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\control\DecisionEngine.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\control\UdpCommandSender.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\control\UdpTelemetryReceiver.kt`

### Web / dashboard
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\web\WebControlServer.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\java\com\mecanum\autocar\web\WebRtcStreamer.kt`
- `D:\GitHub\android_station_image_processing\app\src\main\assets\dashboard.html`

### UI Android
- `D:\GitHub\android_station_image_processing\app\src\main\res\values\strings.xml`
- `D:\GitHub\android_station_image_processing\app\src\main\res\layout\activity_main.xml`

---

## 4. R?ng bu?c b?t bu?c c?a user
1. **M?i text UI ph?i l? ti?ng Vi?t ??ng font, ??ng UTF-8**
2. N?u s?a UI th? ph?i **ki?m tra l?i l?i font ti?ng Vi?t**
3. ?u ti?n:
   - video web m??t
   - ?? tr? th?p
   - manual control ph?n h?i t?c th?
   - kh?ng l?m sai logic ?i?u khi?n xe
4. Kh?ng ???c t?i ?u ki?u l?m h?ng lu?ng Auto / Manual / UDP hi?n c?

---

## 5. Logic kh?ng ???c l?m sai

### Manual priority
- Manual lu?n ?u ti?n h?n Auto
- `StationController.activeManualCommand()` c? deadman timeout `900ms`
- Khi th? n?t / m?t k?t n?i ph?i d?ng manual

### Safety
- STOP ph?i lu?n ?u ti?n cao
- M?t k?t n?i web kh?ng ???c l?m xe gi? l?nh manual m?i
- WebRTC l?i kh?ng ???c ?nh h??ng v?ng ?i?u khi?n xe

### Pipeline
- AI pipeline kh?ng ???c ch? WebRTC
- WebRTC ch? l? consumer c?a frame, kh?ng ???c block `analyzeFrame()`

---

## 6. Tr?ng th?i avoidance hi?n t?i

### ?? n?ng c?p
- Kh?ng c?n logic ch? nh?n ?1 box l?n nh?t ? gi?a? ??n gi?n nh? tr??c
- `DecisionEngine` hi?n d?ng h??ng **obstacle field**:
  - 5 sector ngang
  - risk theo confidence + area + bottom weighting + ROI + soft class weight
  - fuse th?m HC-SR04 v?o center risk
  - temporal smoothing b?ng EMA
  - direction hysteresis / switch penalty ?? gi?m flip tr?i-ph?i
- V?n gi? state machine avoidance c? l?m khung:
  - emergency reverse
  - turn away
  - clear forward
  - reacquire marker

### Soft-whitelist hi?n t?i
- V? class mapping YOLO ch?a x?c minh ??y ??, ?ang d?ng soft policy:
  - class chung v?n ???c t?nh risk
  - nh?ng ROI th?p / v?t ? xa ??nh ?nh / box nh? th? weight th?p ho?c b?
- Khi x?c minh ???c semantic class th?t, c? th? thay sang hard whitelist m? kh?ng ph?i vi?t l?i to?n b? engine

---

## 7. Hardening / crash handling hi?n t?i
- `MainActivity` ?? c? `safeInit()` theo module ?? tr?nh crash d?y chuy?n ? startup
- ?? th?m structured log qua `logModule()` cho:
  - YOLO load
  - ArUco init
  - camera bind
  - web server
  - WebRTC
  - analyzer runtime error
- `ArucoGoalDetector` ?? ki?m tra `OpenCVLoader.initDebug()` v? fail r? n?u OpenCV native kh?ng n?p ???c

---

## 8. T?i ?u performance WebRTC hi?n t?i
- Gi?i h?n stream m?c ??nh: `480x360`, `12 FPS`
- N?u kh?ng c? peer th? `pushFrame()` return s?m
- D?ng frame m?i nh?t, tr?nh t?ch l?y delay
- C? cleanup peer khi disconnect
- ?? t?i ?u convert frame theo h??ng gi?m CPU h?n b?n v?ng l?p Kotlin c?:
  - d?ng `copyPixelsToBuffer()`
  - d?ng `YuvHelper.ABGRToI420()` c?a WebRTC
  - tr?nh t? convert t?ng pixel b?ng Kotlin

---

## 9. Nh?ng ch? n?n ki?m tra ??u ti?n n?u app c?n crash tr?n m?y th?t
1. OpenCV native load
2. TFLite model asset / ABI / delegate
3. Camera permission + bind lifecycle
4. WebRTC init tr?n thi?t b? th?t
5. Runtime analyzer frame ??u ti?n

Khi user b?o crash, ??c Logcat v?i c?c tag ch?nh:
- `MainActivity`
- `WebRtcStreamer`
- l?i native OpenCV / WebRTC / TFLite

---

## 10. C?ch verify sau m?i thay ??i

### Build
- Ch?y: `D:\GitHub\android_station_image_processing\gradlew.bat :app:assembleDebug`

### Check UTF-8 ti?ng Vi?t
- Ki?m tra file:
  - `D:\GitHub\android_station_image_processing\app\src\main\assets\dashboard.html`
  - `D:\GitHub\android_station_image_processing\app\src\main\res\values\strings.xml`
- Kh?ng ch?p nh?n mojibake ki?u:
  - `Tr???m`
  - `D???ng`
  - `?ang k???t n???i`

### Check runtime th?c t?
- M? dashboard b?ng ?i?n tho?i th?t
- Ki?m tra:
  - video c? l?n kh?ng
  - manual hold/release c? m??t kh?ng
  - m?t k?t n?i c? d?ng l?nh kh?ng
  - text ti?ng Vi?t c? l?i font kh?ng

### Check avoidance th?c ??a
- ???ng tr?ng: kh?ng n? v? c?
- M?t v?t c?n gi?a: n? c? ch? ??ch
- Nhi?u v?t trong c?nh: kh?ng flip tr?i/ph?i li?n t?c
- Marker b? che r?i l? l?i: c? quay l?i b?m marker

---

## 11. Ghi ch? m?i tr??ng l?m vi?c r?t quan tr?ng
- PowerShell console c? th? hi?n th? sai font ti?ng Vi?t d? file UTF-8 th?t v?n ??ng ho?c sai kh? ph?n bi?t
- C?ch an to?n h?n:
  - d?ng Python ??c/ghi file UTF-8
  - verify l?i b?ng build ho?c qu?t file UTF-8
- Kh?ng n?n k?t lu?n ??? ??ng font? ch? b?ng output terminal

---

## 12. G?i ? h?nh ??ng cho agent l?n sau
N?u user y?u c?u t?i ?u ti?p, ?u ti?n th? t? sau:
1. ??c file n?y tr??c
2. ??c `MainActivity.kt`, `DecisionEngine.kt`, `WebRtcStreamer.kt`, `WebControlServer.kt`, `dashboard.html`
3. X?c ??nh thay ??i c? ??ng manual safety kh?ng
4. S?a xong ph?i build
5. N?u c? UI th? r? ti?ng Vi?t UTF-8
6. Ghi r? ph?n n?o ?? test build, ph?n n?o c?n test th?t tr?n xe / ?i?n tho?i
