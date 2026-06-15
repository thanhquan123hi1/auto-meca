# Android Station ImgPro

Android Station ImgPro is the Android-side vision and control station for the
mini mecanum autonomous car project. The phone is mounted directly on the car
and replaces the previous laptop-based AI station.

The app uses the phone rear camera as the main visual sensor, runs object
detection locally with TFLite YOLO models, detects ArUco marker ID `0` as the
goal, converts the visual result into a movement command, and sends that command
to the ESP32-S3 firmware over UDP.

This repo intentionally does **not** contain ESP32 C++ firmware. It defines the
contract that firmware must implement.

## Why This Architecture

The old version used this flow:

```text
Camera stream
-> laptop/Node/Python station
-> YOLO + marker detection
-> command server
-> ESP32-S3 vehicle
```

That caused two practical problems during testing:

- camera stream latency and dropped frames;
- unstable FPS because video had to move through several network/software hops.

The new version moves the visual AI onto the phone:

```text
Phone camera
-> Android app local inference
-> local decision engine
-> UDP command
-> ESP32-S3 firmware
-> motor drivers
```

Only a one-character command is sent over the network. The image itself never
needs to leave the phone for the vehicle to drive.

## Runtime Flow

```text
CameraX rear camera frame
-> rotate/convert ImageProxy to Bitmap
-> YOLO TFLite inference for obstacle detection
-> OpenCV ArUco detection for marker ID 0
-> DecisionEngine chooses command
-> UdpCommandSender repeats command every 100 ms
-> ESP32-S3 receives command and drives motors
```

The app also hosts a tiny web page on the phone at port `8088`. A laptop on the
same WiFi can open this page to start/stop autonomous mode and switch models.

The laptop is **only a monitor/control surface**. It is not in the real-time
vision-control loop.

## Main Components

### Android App

- Native Kotlin Android app.
- CameraX for rear camera preview and frame analysis.
- TFLite Interpreter for YOLO inference.
- OpenCV Android for ArUco marker detection.
- A local HTTP server for web Start/Stop and model switching.
- UDP sender for one-character vehicle commands.

### Web Monitor

The web monitor is served by the Android phone, not by a laptop server.

Default URL:

```text
http://<phone-ip>:8088
```

The phone displays this URL at the bottom of the app screen.

The web page currently supports:

- `Start Autonomous`
- `Stop`
- current command
- reason for command
- current AI FPS
- current model
- model switching
- UDP error status

### Firmware Side

Firmware must expose a UDP receiver while the ESP32-S3 is acting as the WiFi AP.
The firmware should keep motor control, PWM ramping, and failsafe logic on the
ESP32 side.

Android only decides and sends the high-level command. Firmware owns the actual
motor behavior for each command.

## Network Contract for Firmware

The Android app expects the ESP32-S3 to create this WiFi AP:

```text
SSID:      Mecanum-Car
Password:  12345678
ESP32 IP:  192.168.4.1
UDP port:  4210
```

The phone must manually connect to `Mecanum-Car` before running the app.

The laptop may also connect to `Mecanum-Car` to open the phone-hosted web page.

## UDP Command Protocol

Android sends UDP datagrams to:

```text
192.168.4.1:4210
```

Each datagram payload is exactly one ASCII command character:

```text
F
B
Q
E
S
```

The app repeats the latest command every `100 ms` while autonomous mode is ON.

When autonomous mode is stopped, the app sends `S` three times immediately and
then stops the repeated sender.

Firmware must not rely on receiving every UDP packet. UDP is intentionally used
for low latency, so packet loss should be tolerated.

## Required Firmware Failsafe

Firmware must implement a command timeout:

```text
If no valid command is received for 500-1000 ms:
    stop all motors
```

This is required because:

- UDP has no delivery guarantee;
- Android may pause, crash, lose WiFi, or switch models;
- the phone may be removed from the car during testing.

The safest behavior is always:

```text
missing command -> S / stop
unknown command -> S / stop
invalid packet -> ignore or stop
```

## Command Meaning

The Android app currently emits these commands:

```text
F = move forward
B = move backward
Q = rotate left
E = rotate right
S = stop
```

The UDP sender also accepts these extra legacy mecanum commands for compatibility:

```text
L = strafe left
R = strafe right
G = forward-left
H = forward-right
J = backward-left
K = backward-right
```

However, the current decision engine only uses:

```text
F B Q E S
```

Firmware team must verify that the motor mapping matches the Android decision
semantics:

```text
Q really rotates the car left
E really rotates the car right
F really moves toward the phone camera direction
B really moves backward
S immediately stops all motors
```

If the car turns the wrong way, fix the firmware motor mapping first. The app
will show the intended command and reason on screen.

## Decision Logic

The app computes commands in this priority order:

1. If autonomous mode is OFF, command is `S`.
2. If a dangerous obstacle is detected, avoid obstacle.
3. Else if ArUco marker ID `0` is visible, navigate toward marker.
4. Else if marker was just lost, stop briefly.
5. Else search for marker using short rotate pulses.

### Obstacle Avoidance

YOLO detections are treated as generic obstacles. The app does not currently
filter by class name because the project goal is avoiding arbitrary objects.

The largest detected object is used as the active obstacle.

Current thresholds:

```text
obstacle_min_area_ratio   = 0.06
obstacle_close_area_ratio = 0.18
```

Behavior:

```text
large obstacle very close:
    command B

obstacle on right side:
    command Q

obstacle on left side:
    command E
```

The screen shows the reason, for example:

```text
obstacle close area=0.22
avoid obstacle right area=0.09
```

### Goal Marker Navigation

The goal is ArUco marker ID `0`.

If marker is left of center:

```text
command Q or S in a pulse cycle
reason: marker left offset=...
```

If marker is right of center:

```text
command E or S in a pulse cycle
reason: marker right offset=...
```

If marker is centered:

```text
command F
reason: marker centered
```

If marker is large enough in the frame:

```text
command S
reason: goal reached area=...
```

Current marker/turn parameters:

```text
turn_deadzone           = 0.16
goal_reached_area_ratio = 0.22
goal_lost_grace_ms      = 450
rotate_pulse_ms         = 120
rotate_pause_ms         = 100
```

Distance-based stopping is prepared in the data model, but current ArUco
detection does not yet estimate physical distance in centimeters. The current
stop condition is marker area ratio.

## AI Models

The app currently ships with four TFLite models:

```text
YOLO26n   default baseline
YOLO11n   lightweight YOLO alternative
YOLOv8n   common YOLO baseline
YOLO26s   larger YOLO26 model for accuracy/FPS comparison
```

Model assets:

```text
app/src/main/assets/yolo26n.tflite
app/src/main/assets/yolo11n.tflite
app/src/main/assets/yolov8n.tflite
app/src/main/assets/yolo26s.tflite
```

The model can be switched from the phone-hosted web page. When switching model,
the app automatically:

```text
stop autonomous mode
send S to the car
close current TFLite interpreter
load selected model
reset FPS measurement
```

This avoids changing model while the car is moving.

## Performance Notes

The app is configured for responsive testing rather than maximum image quality.

Current settings:

```text
ImageAnalysis resolution: 640x480
YOLO input size:          320x320
Inference interval cap:   16 ms
Command repeat interval:  100 ms
TFLite runtime:           CPU threads + NNAPI enabled
```

The app may not reach 60 FPS even with a 16 ms inference cap. Real FPS depends
on:

- phone chipset;
- thermal throttling;
- selected model;
- lighting and camera load;
- ArUco detection cost;
- Android NNAPI behavior.

For final reporting, compare models using:

```text
average FPS after 60 seconds
minimum FPS after 60 seconds
model size
box stability
obstacle detection reliability
command stability
```

Use the same phone, lighting, camera position, obstacle layout, and marker
position for every model.

## Build

Open the repo in Android Studio or build from terminal:

```bash
./gradlew :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Model Export

The helper script exports Ultralytics `.pt` weights to TFLite:

```bash
python3 tools/export_yolo26_tflite.py --weights tools/yolo26n.pt
```

If `--output` is omitted, the script writes to:

```text
app/src/main/assets/<weight-name>.tflite
```

Examples:

```bash
python3 tools/export_yolo26_tflite.py --weights tools/yolo26n.pt
python3 tools/export_yolo26_tflite.py --weights tools/yolo11n.pt
python3 tools/export_yolo26_tflite.py --weights tools/yolov8n.pt
python3 tools/export_yolo26_tflite.py --weights tools/yolo26s.pt
```

The export environment used during development was Python 3.12. Python 3.14 on
macOS failed for TensorFlow/TFLite export, so avoid Python 3.13+ for this export
path on macOS.

## Demo Procedure

1. Flash firmware that exposes the `Mecanum-Car` AP and UDP port `4210`.
2. Install the latest debug APK on the phone.
3. Connect the phone to WiFi `Mecanum-Car`.
4. Open the Android app.
5. Mount the phone in landscape with the rear camera facing forward.
6. Connect laptop to the same `Mecanum-Car` WiFi.
7. Open the URL shown at the bottom of the app, usually:

```text
http://<phone-ip>:8088
```

8. Select the desired model on the web page.
9. Press `Start Autonomous`.
10. Watch `CMD`, `Reason`, and `FPS`.
11. Press `Stop` before touching or lifting the car.

## Firmware Integration Checklist

Before testing with motors enabled:

- ESP32-S3 creates AP `Mecanum-Car`.
- Phone receives an IP on the ESP32 AP.
- Firmware listens on UDP port `4210`.
- Firmware prints/logs each received command.
- Firmware treats unknown command as stop.
- Firmware stops after command timeout.
- Firmware implements `S` as immediate motor stop.
- Firmware verifies `Q` and `E` rotate in the expected directions.
- Firmware verifies `F` moves in the camera-forward direction.

Recommended first integration test:

```text
Motors lifted off the ground
-> Android app connected to AP
-> Web Start
-> move marker left/right/center
-> check firmware serial logs
-> only then allow wheels to touch ground
```

## Troubleshooting

### App shows YOLO error

Check that model files exist under:

```text
app/src/main/assets/
```

Then rebuild and reinstall the APK.

### Bounding boxes are visible but command is wrong

Look at the app `Reason` line:

```text
Reason: marker left offset=...
Reason: avoid obstacle right area=...
```

If the reason is correct but the car moves incorrectly, fix firmware command
mapping.

### Phone FPS drops over time

Likely thermal throttling or NNAPI behavior. Compare models from the web page.
`YOLO26s` is expected to be much slower than nano models.

### Laptop cannot open web page

Both phone and laptop must be connected to the ESP32 AP. The web page is hosted
by the phone, not the ESP32 and not a laptop server.

### Car keeps moving after Stop

Firmware failsafe is not strict enough. Firmware must stop if no valid command
arrives within `500-1000 ms`, and must stop immediately on `S`.
