# 手柄输入与音频模块设计说明

## 适用范围与写作用域

本模块只覆盖以下文件，其余文件（build.sh、AndroidManifest.xml、MainActivity.java、
GameRenderer.java、FrmModel.java、PostFX.java、Car.java、Track.java、GLUtil.java、
Math3D.java、GameState.java、assets/ 下其他文件、server/、net/）不在本模块职责内：

- src/com/forest/racer/input/GamepadInput.java
- src/com/forest/racer/AudioManager.java
- download/audio/ 下全部生成素材与 generate_audio.py
- codedocument/INPUT_AUDIO.md（本文件）

## 一、手柄输入 GamepadInput

类路径：com.forest.racer.input.GamepadInput；纯 Java，依赖 Android SDK，无第三方库，最低 API 24。

### 参考来源

Android 官方文档《Controller input》：
https://developer.android.com/games/sdk/game-controller/controller-input

### S0 契约

- 输入：Android KeyEvent（按键）、MotionEvent.ACTION_MOVE（轴）、InputManager 设备事件。
- 输出：油门 0.0 至 1.0，刹车 0.0 至 1.0，转向 -1.0 至 1.0（负左正右），一次性功能事件枚举。
- 错误边界：无手柄时全部返回 0/false；非法轴值钳制；按键自动重复不产生新事件。
- 线程安全：所有公开方法以 synchronized 保护内部状态。
- 并发约定：事件分发方法应在主线程调用，读取方法可在游戏线程调用。
- 安全评估：仅读取本机输入设备，不联网、不写存储、不申请权限；无注入与越权面。

### 公开方法签名

```
public GamepadInput(InputManager inputManager)
public void release()
public boolean isConnected()
public int getDeviceId()
public float getThrottle()
public float getBrake()
public float getSteer()
public boolean isStartPressed()
public Event[] consumeEvents()
public boolean dispatchKeyEvent(KeyEvent event)
public boolean dispatchGenericMotionEvent(MotionEvent event)
public void refreshDevices()
public void onInputDeviceAdded(int addedId)
public void onInputDeviceRemoved(int removedId)
public void onInputDeviceChanged(int changedId)
public enum Event { START, BACK, MUSIC, TRACK }
```

### 轴映射（多映射兼容）

- 转向：AXIS_X / AXIS_Z / AXIS_HAT_X 取绝对值最大者，死区 0.12。
- 油门：优先 AXIS_THROTTLE / AXIS_RTRIGGER，降级 AXIS_GAS、-AXIS_Y，死区后钳制 0 至 1。
- 刹车：优先 AXIS_BRAKE / AXIS_LTRIGGER，降级 AXIS_Y / AXIS_RZ，死区后钳制 0 至 1。
- 特殊手柄：仅上报 AXIS_Z/RZ 且无 AXIS_X 时，将其按扳机处理（取绝对值），
  转向回退到 AXIS_X。
- 死区公式：value * (|value| - 0.12) / (1 - 0.12)，消除摇杆漂移。

### 按键映射

- KEYCODE_BUTTON_A：START 事件 + isStartPressed() 持续态。
- KEYCODE_BUTTON_B：BACK 事件。
- KEYCODE_BUTTON_X：MUSIC 事件（切音乐）。
- KEYCODE_BUTTON_Y：TRACK 事件（切赛道）。
- 事件去重：同一按键 180 ms 内的重复按下忽略；ACTION_UP 清除按下状态。

### 热插拔

- 构造时调用 InputManager.registerInputDeviceListener 并主动扫描设备。
- onInputDeviceAdded / onInputDeviceRemoved / onInputDeviceChanged 更新连接状态。
- 拔出手柄时清零全部轴值与按键状态，并自动扫描其余手柄。
- refreshDevices() 供宿主在 Activity 生命周期回调中兜底调用。
- release() 注销监听，避免 Activity 泄漏。

### 集成要求（lead 侧）

1. Activity.onCreate 中：
   `gamepad = new GamepadInput((InputManager) getSystemService(Context.INPUT_SERVICE));`
2. Activity.onKeyDown/onKeyUp 返回 `gamepad.dispatchKeyEvent(event)`。
3. Activity.dispatchGenericMotionEvent 返回 `gamepad.dispatchGenericMotionEvent(event)`。
4. 游戏循环每帧读取 getThrottle/getBrake/getSteer，并 consumeEvents() 处理菜单/切歌/切赛道。
5. Activity.onDestroy 调用 gamepad.release()。

## 二、音频 AudioManager

类路径：com.forest.racer.AudioManager；包内可见（final class），纯 Java + Android SDK。

### S0 契约

- 输入：Context；assets/audio/ 下素材；speedRatio、skidRatio 为 0.0 至 1.0；gear 为整数挡位。
- 输出：void 播放控制；boolean isMusicOn；int getTrackIndex/getTrackCount。
- 错误边界：素材缺失时 load 返回 0，对应播放静默跳过；init 失败置 ready=false，不崩溃。
- 线程安全：公开方法 synchronized；音乐 prepare 在调用线程完成。
- 资源上限：SoundPool 最大 16 路；循环声只启动一次，release 后全部释放。
- 安全评估：仅读取 assets 本地素材，不联网、不写外部存储；无权限申请。

### 向后兼容方法（原签名不变）

```
void init()
void startMusic()
void pauseMusic()
void setMusicOn(boolean on)
boolean isMusicOn()
void startEngine()
void updateEngine(float speedRatio)
void beep()
void crash()
void release()
```

### 新增方法

```
void updateDrive(float speedRatio, float skidRatio, boolean braking)
void playSkid()
void playBrake()
void playGear(int gear)
void playCountdown()
void playWin()
void playLose()
void startAmbient()
void stopAmbient()
void nextTrack()
int  getTrackIndex()
int  getTrackCount()
```

### 行为说明

- 引擎交叉淡入：低频层音量 0.13 至 0.07，高频层 0.02 至 0.18，播放速率随速度提升；
  低频素材缺失时回退到原 audio/engine.wav。
- 风噪：速度平方映射音量，速率 0.8 至 1.4，循环播放。
- 胎噪：随 skidRatio 线性淡入；playSkid() 提供短促一次性版本。
- 刹车：braking=true 时触发，内置 300 ms 节流避免每帧重复播放；也可独立调用 playBrake()。
- 换挡：gear 与上次相同则不播放，避免重复。
- 音乐：TRACKS = music.ogg, music2.ogg, music3.ogg；nextTrack() 循环切换并保持播放状态。
- 环境音：startAmbient() 循环播放 ambient_birds.ogg，stopAmbient() 停止。

### 集成要求（lead 侧）

- 初始化：`audio.init(); audio.startMusic(); audio.startEngine(); audio.startAmbient();`
- 游戏循环：`audio.updateDrive(playerSpeed / maxSpeed, skidAmount, isBraking);`
- 挡位变化时：`audio.playGear(currentGear);`
- 倒计时每秒：`audio.playCountdown();`
- 结算：`audio.playWin()` 或 `audio.playLose()`。
- X 键：`audio.nextTrack();`
- onDestroy：`audio.release();`
- 注意：素材生成在 download/audio/，需 lead 复制或配置打包到 assets/audio/ 后构建 APK。

## 三、音频素材清单与来源

所有素材由 download/audio/generate_audio.py 使用 numpy 程序化合成，
无外部版权素材。WAV 为 44100 Hz 单声道 16 bit；OGG 为 libvorbis q4，44100 Hz。

| 文件 | 用途 | 时长/说明 |
|---|---|---|
| engine_low.wav | 引擎低频层 | 2.0 s 循环，58 Hz 基频，10 次谐波 |
| engine_high.wav | 引擎高频层 | 2.0 s 循环，132 Hz 基频，14 次谐波 |
| tire_skid.wav | 胎噪 | 1.6 s 循环，高频滤波噪声 + 摩擦尖叫 |
| wind.wav | 风噪 | 2.0 s 循环，低通噪声 + 阵风包络 |
| brake.wav | 刹车 | 0.70 s 单次，下滑频率 + 噪声 |
| gear_shift.wav | 换挡 | 0.35 s 单次，咔嗒 + 气流 |
| countdown.wav | 倒计时 | 0.18 s 单次，880 Hz 提示音 |
| win.wav | 胜利 | 1.6 s 单次，上行琶音 + 和弦 |
| lose.wav | 失败 | 1.5 s 单次，下行音高 |
| ambient_birds.ogg | 森林鸟鸣 | 10 s 循环频域低通底噪 + 26 组扫频鸟叫 |
| music2.ogg | 轻音乐二 | C 大调五声音阶，96 BPM，8 小节 |
| music3.ogg | 轻音乐三 | A 小调五声音阶，84 BPM，8 小节 |

原有素材 assets/audio/engine.wav、beep.wav、crash.wav、music.ogg 保持不动，
作为兼容回退与前奏音乐使用。

### 素材验证结果（2026-11-26 实测）

逐个文件验证存在、非空且可解码，12/12 全部通过：

```
PASS engine_low.wav   176444 B  ch=1 rate=44100 frames=88200
PASS engine_high.wav  176444 B  ch=1 rate=44100 frames=88200
PASS tire_skid.wav    141164 B  ch=1 rate=44100 frames=70560
PASS wind.wav         176444 B  ch=1 rate=44100 frames=88200
PASS brake.wav         61782 B  ch=1 rate=44100 frames=30869
PASS gear_shift.wav    30912 B  ch=1 rate=44100 frames=15434
PASS countdown.wav     15920 B  ch=1 rate=44100 frames=7938
PASS win.wav          141164 B  ch=1 rate=44100 frames=70560
PASS lose.wav         132344 B  ch=1 rate=44100 frames=66150
PASS ambient_birds.ogg 88422 B  codec=vorbis rate=44100
PASS music2.ogg        76826 B  codec=vorbis rate=44100
PASS music3.ogg        80670 B  codec=vorbis rate=44100
RESULT ALL_PASS
```

### 编译验证（2026-11-26 实测）

```
javac -source 8 -target 8 -classpath <android.jar> AudioManager.java GamepadInput.java
javac-exit=0
输出：AudioManager.class, GamepadInput.class, GamepadInput$Event.class
```

仅有 -source 8 引导类路径警告，无错误。

## 四、已知限制与待确认

- Android 真机手柄映射差异较大，当前映射覆盖 AXIS_X/Z/HAT_X 与两组扳机轴，
  未在实体手柄上实测；需 lead 在 ADB 设备上实机验证。
- 生成脚本使用 ffmpeg 编码 OGG；若构建环境无 ffmpeg，需预先保留已生成的 .ogg 文件。
- 素材当前在 download/audio/，需由 lead 纳入 APK 打包流程。
