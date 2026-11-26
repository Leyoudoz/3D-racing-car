# 坐标系与转向约定（设计基准，禁止随意更改）

## 为什么有这份文档
开发过程中转向左右符号被反复修改过多次（左→右→左），根因是没有先定死坐标系约定就靠截图试错。这份文档把约定固化，后续任何转向/相机/AI 修改都必须先对照这里，不再凭感觉改符号。

## 坐标系（右手系，OpenGL 标准）
- +Y 向上，+X 向右，+Z 朝向屏幕外（观察者）
- 绕 +Y 轴的旋转：从 +Y 轴正方向向下俯视，逆时针为正（右手定则）
- 依据：UIC 计算机图形学讲义 https://www.cs.uic.edu/~jbell/CourseNotes/ComputerGraphics/Coordinates.html

## 车辆朝向（唯一约定）
- 车头前进方向：forward = (sin(yaw), 0, cos(yaw))
- yaw = 0 时车头朝 +Z
- 相机右向量 = cross(up, z_back) = (-1,0,0)（yaw=0 时），即世界 -X 是屏幕右
- 因此：右转 = 车头偏向 -X；左转 = 车头偏向 +X

## 转向符号（唯一约定，不得反复改）
- steer 输入范围 [-1, +1]
- steer = +1 表示玩家按"右"键，期望车头向右转
- steer = -1 表示玩家按"左"键，期望车头向左转
- 转向更新：yaw -= turnRate * dt，其中 turnRate = (speed/24) * steer * 1.85

### 数学验证（2026-11-26 实测修正，之前版本结论错误）
yaw=0 时 forward=(0,0,1)，相机在车后 -Z 看向 +Z。
- lookAt 的右向量 x = cross(up, z)，z = eye-center = (0,0,-1)
- x = cross((0,1,0),(0,0,-1)) = (-1,0,0)，即屏幕右 = 世界 -X
- yaw 增加 ε：forward≈(ε,0,1)，车头偏向 +X = 屏幕左 → yaw 增加 = 左转
- 故右转（steer=+1）必须让 yaw 减小：yaw -= turnRate*dt
- 权威佐证：LearnOpenGL（OpenGL 右手系）、Microsoft DirectXMath（右手系角度取负）
- 实测脚本：cross(forward,up)=(-cos yaw,0,sin yaw)；d(forward)/d(yaw)=(cos,0,-sin)=-right(屏幕)=左

## 相机（跟随，在车后方）
- camTarget = 车位置 + (0, 1.2, 0)
- camPos = 沿赛道中心线后方 pointAt(trackT-1.6) + (0, 地形钳制高度, 0)
- 相机在车后方，看向车前方；屏幕右对应世界车体的 -right 方向

## 触摸输入映射
- 左按键 → touchLeft → steer 贡献 -1
- 右按键 → touchRight → steer 贡献 +1
- 两者同步按 → steer = 0

## 手柄输入映射
- AXIS_X / AXIS_Z / HAT_X：正方向视为右（+1），与触摸一致
- GamepadInput.getSteer() 返回 [-1,+1]，直接作为 steer

## 修改禁令
- 不得因为"感觉方向反了"单独翻转某一处符号。若要改，必须同时改：转向更新、相机、AI 循迹、触摸映射、手柄映射，并更新本文档的验证推导。
- AI 循迹 yawAt 用 atan2(dx, dz)，与 forward=(sin,cos) 一致（atan2(x,z) 返回的角度满足 sin=yaw? x/len, cos=z/len）。
