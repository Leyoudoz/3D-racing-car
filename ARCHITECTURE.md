# 架构

纯 Java GLES3 渲染，Activity + GLSurfaceView 主循环。

包结构：
- com.forest.racer.MainActivity：入口、生命周期、音频管理
- com.forest.racer.GameRenderer：GLES3 渲染器、阴影 pass、主 pass
- com.forest.racer.GameState：状态机与物理更新
- com.forest.racer.Track：21 条赛道程序化生成，样条中心线、高度、宽度、树木布置
- com.forest.racer.Car：跑车网格、操控物理、AI 循迹
- com.forest.racer.Mesh / Shader / Texture：渲染基础设施
- com.forest.racer.audio.AudioManager：轻音乐与音效

渲染管线：
1. 阴影 pass：光源正交投影，深度 FBO 2048x2048，渲染车辆与树木。
2. 主 pass：透视相机，逐物体绘制，纹理 + 法线光照 + 阴影采样 + 雾。
3. HUD：GLES 叠加文字（速度、名次、圈数、赛道名、计时）。

赛道生成：
- 21 条固定种子，闭合 Catmull-Rom 样条，半径 60-140m，宽度 10-16m。
- 沿中心线生成路面带（沥青贴图）、路肩（草地/砾石）、护栏、树木（每赛道 300-600 棵）。
- 高度起伏用低频正弦叠加，坡度受限。

物理：
- 玩家：加速/刹车/转向，简单侧向抓地力模型，速度上限 70 m/s。
- 机器人：沿中心线前瞻点转向，目标速度按弯道曲率调整，带随机扰动形成差异。

音频：
- 轻音乐 OGG 循环（MediaPlayer），引擎音 WAV（SoundPool 循环），碰撞音（SoundPool）。
