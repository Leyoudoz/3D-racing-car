# 引擎迁移计划：纯 Java GLES3 -> Godot 4.6.1

## 决策
2026-11-26 实测确认：GitHub 直连被拒，ghproxy 镜像可用。Godot 4.6.1-stable 为当前稳定版（2026-02-16 发布，官网 + GitHub API 双源确认）。

迁移原因：纯 Java 自研渲染画质上限过低（十字面片树、纯色天空、纸片感），无法达到用户要求的"现实风格大型游戏"。Godot 4 提供 PBR、HDRI 天空、完整光照/后处理、GLB 高模直接导入、LOD 自动生成，画质基础到位。

## 时间基线
2026-11-26 16:59 CST（date 实测）

## 复用资产（不浪费之前的工作）
- 高模跑车 GLB 3 台（vinish.dev CC BY 4.0，13-17 万面）：download/models/*.glb —— Godot 直接导入
- CC0 PBR 贴图（Poly Haven）：download/textures/、download/env/ —— 直接导入
- CC0 HDRI 天空：download/sky/*.jpg —— Godot WorldEnvironment 用
- CC0 树木/岩石 GLB（Gobkit）：download/trees/*.glb —— 直接导入，替换十字面片
- 音效/音乐 16 个：download/audio/（input-audio 生成）—— 直接导入
- C++ 权威服务端：server/ —— 完全保留，Godot 客户端用 UDP 对接

## 重写内容
- 游戏逻辑 -> GDScript：21 条赛道生成、车辆物理、AI 循迹、比赛状态机
- 渲染 -> Godot 场景：WorldEnvironment（HDRI + SSAO + 辉光 + 色调映射）、DirectionalLight3D（阴影）、高模车实例化
- 输入 -> Godot InputMap：触摸虚拟按键 + 手柄（Godot 原生支持手柄轴/按键）
- 网络 -> GDScript 对接现有 NetClient 协议（或 UDP 直连）

## 构建流程
- Godot 无头导入：`godot --headless --editor --quit-after 5 --path <project>`
- Android 导出：`godot --headless --export-release "Android" <out.apk> --path <project>`
- 需 export_templates.tpz + Android SDK(34) + build-tools(34) + JDK17，本机均已具备

## 目标
- 高模车 + HDRI 天空 + 真实树木 + PBR 地面 + 后处理，达到"现实风格"
- 21 赛道 + 比赛/自由漫游双模式
- 手柄 + 触摸双输入
- C++ 服务端联机
- ADB 安装实测 + 逐张截图验收
