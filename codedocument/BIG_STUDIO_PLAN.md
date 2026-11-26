# 大厂级重构方案（对标巅峰极速）

## 时间基线
2026-11-26 16:20 CST（date 实测）

## 用户批评点与对应整改
| 批评 | 整改 |
|---|---|
| 地图 1 不完整 | 重建赛道生成：完整闭合路面、路肩、护栏、地形、植被分层，逐张截图验收 |
| 不看着质量调试 | 每次构建后 ADB 截图 + 人工看图，不达标不继续 |
| 对标巅峰极速 | 采用高模跑车 + PBR + HDR 后处理 + 完整赛道环境 |
| 要手柄 + 触摸 | InputDevice API 手柄轴/按键 + 现有触摸，双输入并存 |
| 模型太低模 | 引入 13-17 万三角面高模跑车，PBR 贴图法线，运行时按材质分 draw call |
| 高模转低模逻辑 | 下载高模为源；运行时静态批处理 + LOD；离线烘焙法线贴图已在素材内 |
| 缺资料 | 检索：巅峰极速官方、GLB 高模、HDR/Bloom/SSAO、Android 手柄 API、权威服务端架构 |
| 省 token | 完整实现，不缩减 |
| 电影级光影 | HDR 浮点 FBO + ACES 色调映射 + Bloom + 阴影 + 半球光 + 雾 + SSAO（可选） |
| 服务端 C++ + 客户端 | C++ 权威服务端（UDP tick、房间、物理、比赛判定），客户端预测和解 |
| 音效太少 | 引擎分层、胎噪、风噪、碰撞、刹车、倒计时、环境鸟鸣、多首音乐 |
| 太垃圾/小游戏 | 按上述全部重做 |

## 目标清单（5 维，可验证）
1. 正确性：21 条赛道全部能进入且闭合；比赛 3 圈完赛判定；手柄与触摸都能操控；客户端能连服务端。验证：ADB 自动化 + 截图 + 日志。
2. 安全：客户端输入服务端校验（速度/位置钳制）；无越权房间操作；无明文密钥。验证：服务端单测 + 构造非法包测试。
3. 性能：x86_64 虚拟机 30 FPS 以上；高模车 6 台可跑；内存峰值 < 400MB。验证：logcat 帧时统计 + dumpsys meminfo。
4. 可维护：模块分层（渲染/输入/物理/网络/音频），GLB 加载器独立；C++ 服务端独立可编译。验证：编译通过 + 目录结构审查。
5. 交付面：APK 可安装运行；服务端二进制可运行；21 赛道截图抽检；更新日志与检索表齐全；git 提交打 tag。

## 技术选型
- 高模跑车：vinish.dev CC BY 4.0，中置超跑/后置超跑/GT，13-17 万面，含 baseColor/法线/粗糙度贴图。署名写入 CREDITS。
- GLB 解析：自研 Python 离线转换器 → 紧凑二进制（顶点位置/法线/UV/索引 + 材质参数 + 贴图 PNG/JPG），Java 运行时加载 GLES3。理由：无 NDK、无第三方依赖、包体可控。
- 高模到低模：素材本身是高模；转换时保留高模细节，按材质合并 draw call；远景车用 LOD（降采样索引）。法线贴图来自高模烘焙（素材自带 normal）。
- 光影：GLES3 多重渲染目标，RGBA16F 场景 FBO（若不支持降级 RGBA8），亮度提取 + 高斯模糊 Bloom + ACES tone mapping。
- 输入：Android InputDevice，ACTION_GENERIC_MOTION 读 AXIS_X/AXIS_Z/AXIS_BRAKE/AXIS_THROTTLE，KEYCODE_BUTTON_A 等；触摸保留。
- 服务端：C++17，UDP，固定 60Hz tick，房间管理，车物理权威模拟，快照广播，客户端预测 + 和解。

## 参考来源（一手）
- vinish.dev 高模跑车（CC BY 4.0）：https://vinish.dev/free-3d-models/realistic-cars
- Khronos glTF 2.0 规范：https://github.com/KhronosGroup/glTF/tree/main/specification/2.0
- Android 手柄输入：https://developer.android.com/games/sdk/game-controller/controller-input
- LearnOpenGL HDR：https://learnopengl.com/Advanced-Lighting/HDR
