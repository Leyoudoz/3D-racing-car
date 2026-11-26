package com.forest.racer;

/** 跑车实体：高模渲染 + 驾驶物理 + AI 循迹。单位米、弧度、米每秒。 */
final class Car {
    /** 车辆工厂：渲染器实现，负责提供高模与颜色。 */
    interface Factory {
        Car create(Track track, boolean player, int aiIndex, int colorIndex);
    }

    static final int MAX_SPEED = 68;      // m/s，约 245 km/h
    static final float LENGTH = 4.4f, WIDTH = 1.95f;

    final FrmModel model;      // 高模（车身 + 四轮节点）
    final int colorIndex;
    final boolean player;

    // 世界状态
    float x, y, z, yaw;
    float speed, steer, lateral;
    float trackT;
    int lap;
    float lapTime, bestLap;
    int place;
    float enginePitch;
    float wheelRoll;           // 车轮滚动角，弧度
    float bodyRoll;            // 车身侧倾，弧度
    int startLapIdx;           // 发车时所在的圈索引，用于计算已完成圈数

    private final Track track;
    private final float aiSkill;
    private float aiWobble;
    final VehiclePhysics physics = new VehiclePhysics();

    Car(Track track, boolean player, int aiIndex, int colorIndex, FrmModel model) {
        this.track = track;
        this.player = player;
        this.colorIndex = colorIndex;
        this.model = model;
        this.aiSkill = player ? 0f : 0.84f + aiIndex * 0.028f;
        this.aiWobble = aiIndex * 1.7f;
        reset(aiIndex);
    }

    void reset(int gridIndex) {
        // 发车格在起跑线(t=0)后方，pole 最靠前
        trackT = -(gridIndex * 1.5f) - 2f;
        startLapIdx = (int) Math.floor(trackT / track.size()); // 通常为 -1
        float[] p = track.pointAt(trackT);
        yaw = track.yawAt(trackT);
        // 赛道法线（水平右向）：yaw 方向的前向为 (sin,cos)，右向为 (cos,-sin)
        float nx = (float)Math.cos(yaw), nz = -(float)Math.sin(yaw);
        float side = (gridIndex % 2 == 0 ? -1.8f : 1.8f);
        x = p[0] + nx * side;
        z = p[2] + nz * side;
        y = p[1];
        speed = 0; steer = 0; lateral = 0; lap = 0; lapTime = 0; bestLap = 0;
        place = gridIndex + 1; wheelRoll = 0; bodyRoll = 0;
        physics.speed = 0; physics.vy = 0; physics.yawRate = 0;
        physics.steerAngle = 0; physics.wheelRoll = 0;
    }

    void updatePlayer(float dt, float throttle, float brake, float steerInput) {
        physics.update(dt, throttle, brake, steerInput);
        applyMotion(dt);
    }

    void updateAI(float dt, float time) {
        // Stanley 循迹：航向误差 + 横向偏差，两者都转成转向输入。
        float look = 3.0f + physics.speed * 0.06f;
        float[] target = track.pointAt(trackT + look);
        float desiredYaw = (float) Math.atan2(target[0]-x, target[2]-z);
        float headingErr = wrapAngle(desiredYaw - yaw);   // >0 需左转

        // 横向偏差：车到最近中心线点的有符号距离（相对赛道右向）
        float[] cp = track.pointAt(trackT);
        float tYaw = track.yawAt(trackT);
        float trx = (float)Math.cos(tYaw), trz = -(float)Math.sin(tYaw);  // 赛道右向
        float lateralErr = (x-cp[0])*trx + (z-cp[2])*trz;                 // >0 车在右侧
        // 车在右侧需左转回去 → steer 负；用 softening 防除零
        float crossTrackTerm = (float) Math.atan(-1.5f * lateralErr / (physics.speed + 6f));

        aiWobble += dt * 0.55f;
        float wobble = (float)Math.sin(aiWobble) * 0.015f * (1f - aiSkill);
        // 航向误差 + 横向偏差；diff>0(左转需求) → steer 负
        float steerInput = Math3D.clamp(-(headingErr + crossTrackTerm + wobble) * 1.6f, -1f, 1f);

        // 物理前瞻减速：前瞻距离随速度增加（约刹车距离 v²/2a，a≈8m/s²），
        // 扫描前方曲率并用侧向加速度上限 8m/s² 反推安全速度。
        // 经 PhysicsSim 单测：缓弯(R111)与急弯(R70)下 6 台 AI 均稳定在赛道内。
        float lookAhead = 5f + physics.speed * physics.speed / 16f;
        float curve = 0f;
        for (float d = 0f; d < lookAhead; d += 2f) {
            curve = Math.max(curve,
                    Math.abs(wrapAngle(track.yawAt(trackT + d + 2f) - track.yawAt(trackT + d))));
        }
        float curvPerM = curve / 2f;
        float safeSpeed = (float) Math.sqrt(8f / Math.max(0.005f, curvPerM));
        float targetSpeed = Math.min(MAX_SPEED * aiSkill, safeSpeed);
        targetSpeed = Math.min(targetSpeed, 26f + aiSkill * 10f);
        float throttle = physics.speed < targetSpeed ? 0.75f : 0f;
        float brake = physics.speed > targetSpeed + 2f ? 0.6f : 0f;

        physics.update(dt, throttle, brake, steerInput);
        applyMotion(dt);
    }

    private void applyMotion(float dt) {
        // 从真实车辆动力学取输出
        float spd = physics.forwardSpeed();
        float vy = physics.lateralSpeed();
        float yr = physics.yawRate();

        // 横摆积分：yawRate 已含正确符号（右转为负）
        yaw += yr * dt;

        // 世界坐标积分：forward=(sin,cos)，车体右向=(-cos,sin)（屏幕右）
        float fx = (float)Math.sin(yaw), fz = (float)Math.cos(yaw);
        float rx = -(float)Math.cos(yaw), rz = (float)Math.sin(yaw);
        x += (fx*spd + rx*vy) * dt;
        z += (fz*spd + rz*vy) * dt;

        // 车身侧倾：随横向加速度，限制幅度
        float targetRoll = -Math3D.clamp(physics.lateralAccel / 9.81f, -1f, 1f) * 0.05f;
        bodyRoll += (targetRoll - bodyRoll) * Math.min(1f, dt * 6f);

        // 同步供渲染/HUD/音频使用的字段
        speed = spd;
        lateral = vy;
        steer = -physics.steerAngle / (float)Math.toRadians(34f);  // 归一化到 -1..1
        wheelRoll = physics.wheelRoll;

        float best = trackT, bestD = Float.MAX_VALUE;
        for (int d = -2; d <= 8; d++) {
            float t = trackT + d;
            float[] p = track.pointAt(t);
            float dist = (p[0]-x)*(p[0]-x) + (p[2]-z)*(p[2]-z);
            if (dist < bestD) { bestD = dist; best = t; }
        }
        trackT = best;
        float[] gp = track.pointAt(trackT);
        y += (gp[1]-y) * Math.min(1f, dt*8f);

        // 出界：草地增加滚阻并推回（比原来温和，保留真实动力学）
        float off = (float) Math.sqrt(bestD);
        if (off > gp[3]) {
            physics.speed *= (float) Math.pow(0.45, dt);
            float push = (off - gp[3]) * Math.min(1f, dt*4f);
            if (off > 0.001f) {
                x += (gp[0]-x)/off * push;
                z += (gp[2]-z)/off * push;
            }
        }

        int n = track.size();
        int curLapIdx = (int) Math.floor(trackT / n);
        lap = Math.max(0, curLapIdx - startLapIdx);
        enginePitch = speed / MAX_SPEED;
    }

    static float wrapAngle(float a) {
        while (a > (float)Math.PI) a -= 2f*(float)Math.PI;
        while (a < -(float)Math.PI) a += 2f*(float)Math.PI;
        return a;
    }

    float progress() { return lap * track.totalLength + trackT / track.size() * track.totalLength; }
}
