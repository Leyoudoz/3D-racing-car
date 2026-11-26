# 森林极速 (Forest Racer)

> **归档声明（2026-11-26）：本项目已归档，不再维护与更新。** 感谢所有关注。

一款面向 Android 的 3D 赛车游戏：森林赛道、高模跑车、机器人对手、真实车辆物理、客户端/服务端联机。纯 Java + OpenGL ES 3.0 客户端，C++17 权威 UDP 服务端。

## 状态

| 项目 | 状态 |
|---|---|
| 版本 | v01.02.00.00 |
| 归档日期 | 2026-11-26 |
| 维护 | 已停止，不再接受 issue / PR |
| 许可证 | 代码见 LICENSE；第三方素材见下方署名 |

## 功能

- 21 条程序化森林赛道，闭合样条 + 地形起伏 + 真实 3D 树木/护栏/路灯
- 3 台高模跑车（13–17 万三角面，PBR 材质），含降面 LOD
- 真实车辆动力学：Pacejka 魔术公式轮胎、载荷转移、摩擦圆、单轨横摆模型
- AI 机器人对手：物理前瞻减速循迹，经单测在缓弯/急弯均不出界
- 比赛模式（3 圈 6 车）与自由漫游模式
- 触摸虚拟按键 + 键盘 + 手柄三种操控；手柄连接自动隐藏虚拟按键
- 线性工作流 + ACES 色调映射；SwiftShader 软件渲染自动降级
- C++17 权威 UDP 服务端：房间管理、输入校验、60Hz tick、客户端预测与和解
- 16 个音效/音乐素材

## 目录结构

```
.
├── src/com/forest/racer/   # 客户端 Java 源码
│   ├── GameRenderer.java   # GLES3 渲染（阴影/PBR/后处理）
│   ├── VehiclePhysics.java # 真实车辆物理
│   ├── Car.java            # 车辆状态与 AI
│   ├── Track.java          # 21 条赛道生成
│   ├── FrmModel.java       # 高模 FRM1 加载器
│   ├── PostFX.java         # HDR/ACES 后处理
│   ├── input/              # 手柄输入
│   └── net/                # 客户端网络（预测+和解）
├── server/                 # C++ 权威服务端（含 Makefile/构建脚本）
├── tests/                  # 服务端单测、物理仿真单测、端到端测试
├── assets/                 # 模型/贴图/音频（打包进 APK）
├── codedocument/           # 设计契约、坐标约定、协议、研究报告、更新日志
├── AndroidManifest.xml
├── build.sh                # 纯 Android SDK 构建脚本
└── LICENSE
```

## 构建

### 客户端 APK
需要 Android SDK（build-tools 34、platform android-34）与 JDK 17。设置 `ANDROID_HOME`/`JAVA_HOME` 后：

```bash
bash build.sh
# 输出 versions/ForestRacer-v01.02.00.00.apk
```

构建链：`aapt2` 编译资源 → `javac` → `d8` → `zipalign` → `apksigner`，无 Gradle / NDK / 第三方引擎依赖。

### 服务端
```bash
cd server
./build_server.sh
./forest_racer_server --port 7777
```

## 测试

```bash
# 服务端 23 项单元测试
g++ -std=c++17 -O2 -Wall -Wextra -Iserver tests/server_test.cpp -o /tmp/fr_test && /tmp/fr_test

# AI 循迹物理仿真（缓弯/急弯 6 车不出界）
javac -d /tmp/fr_sim tests/PhysicsSim.java && java -cp /tmp/fr_sim PhysicsSim

# UDP 端到端
./tests/test_net.sh
```

## 操作

- 触摸：屏幕底部虚拟按键（左/右/刹车/油门）
- 键盘：WASD（W 油门 / S 刹车 / A 左 / D 右）
- 手柄：摇杆转向 + 扳机油门刹车；A 开始 / B 返回 / X 音乐 / Y 切赛道
- 手柄连接后虚拟按键自动隐藏，触摸屏幕即恢复

## 第三方素材署名

本项目使用以下 CC0 / CC BY 4.0 素材，版权归原作者所有：

- **跑车模型**：vinish.dev（CC BY 4.0），`https://vinish.dev/free-3d-models/realistic-cars`
- **天空 HDRI / 地面 PBR / 路边道具**：Poly Haven（CC0），`https://polyhaven.com`
- **树木/灌木/岩石**：Gobkit Nature Kit（CC0），`https://gobkit.itch.io`
- 完整来源、URL、许可证与尺寸见 `codedocument/` 中的素材清单。

## 已知限制

- 开发测试环境为 Google SwiftShader 纯软件渲染（无GPU），实测约 8 FPS；真机 GPU 会自动启用全质量（高模 + PBR + 阴影 + Bloom）。
- 碰撞为赛道边界推回模型，非完整刚体 OBB 碰撞。
- Godot 引擎迁移实验因 SwiftShader 不兼容未采用，相关记录见 `codedocument/GODOT_MIGRATION.md`。

## 归档说明

本项目为阶段性成果归档，代码与素材冻结于 v01.02.00.00。**后续不再维护、不再更新、不接受 issue 与 PR。** 如需继续开发，请自行 fork。
