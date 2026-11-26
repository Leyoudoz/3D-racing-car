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

    private final Track track;
    private final float aiSkill;
    private float aiWobble;

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
        trackT = (float) (track.size() - 3 - gridIndex * 1.5f);
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
    }

    void updatePlayer(float dt, float throttle, float brake, float steerInput) {
        steer += (steerInput - steer) * Math.min(1f, dt * 11f);
        // 分段加速度：起步猛、高速渐缓，阻力更小，手感更接近大马力跑车
        float power = 34f * (1f - 0.55f * (speed / MAX_SPEED));
        float accel = throttle * power - brake * 46f - speed * 0.06f;
        speed = Math3D.clamp(speed + accel * dt, 0, MAX_SPEED);
        applyMotion(dt);
    }

    void updateAI(float dt, float time) {
        float look = 2.5f + speed * 0.045f;
        float[] target = track.pointAt(trackT + look);
        float desiredYaw = (float) Math.atan2(target[0]-x, target[2]-z);
        aiWobble += dt * 0.7f;
        desiredYaw += (float) Math.sin(aiWobble) * 0.02f * (1f - aiSkill) * 6f;
        float diff = wrapAngle(desiredYaw - yaw);
        float steerInput = Math3D.clamp(diff * 2.4f, -1f, 1f);
        steer += (steerInput - steer) * Math.min(1f, dt * 7f);

        float[] ahead = track.pointAt(trackT + 5f);
        float[] here = track.pointAt(trackT);
        float curvature = (float) Math.abs(wrapAngle(
                (float)Math.atan2(ahead[0]-here[0], ahead[2]-here[2]) - yaw));
        float targetSpeed = (MAX_SPEED * aiSkill) * (1f - Math3D.clamp(curvature * 1.4f, 0, 0.62f));
        speed += Math3D.clamp(targetSpeed - speed, -30f*dt, 18f*dt);
        applyMotion(dt);
    }

    private void applyMotion(float dt) {
        float turnRate = (speed / 24f) * steer * 1.85f;
        yaw += turnRate * dt;
        float slip = speed * steer * 0.09f;
        lateral += (slip - lateral) * Math.min(1f, dt * 5f);
        float fx = (float)Math.sin(yaw), fz = (float)Math.cos(yaw);
        float rx = (float)Math.cos(yaw), rz = -(float)Math.sin(yaw);
        x += (fx*speed + rx*lateral) * dt;
        z += (fz*speed + rz*lateral) * dt;

        // 车身侧倾（随转向与速度），限制幅度
        float targetRoll = -steer * Math.min(1f, speed / 30f) * 0.06f;
        bodyRoll += (targetRoll - bodyRoll) * Math.min(1f, dt * 6f);
        // 车轮滚动：轮胎半径约 0.345m
        wheelRoll += (speed / 0.345f) * dt;

        float best = trackT, bestD = Float.MAX_VALUE;
        for (int d = -2; d <= 8; d++) {
            float t = trackT + d;
            float[] p = track.pointAt(t);
            float dist = (p[0]-x)*(p[0]-x) + (p[2]-z)*(p[2]-z);
            if (dist < bestD) { bestD = dist; best = t; }
        }
        float prev = trackT;
        trackT = best;
        float[] gp = track.pointAt(trackT);
        y += (gp[1]-y) * Math.min(1f, dt*8f);

        float off = (float) Math.sqrt(bestD);
        if (off > gp[3]) {
            speed *= (float) Math.pow(0.25, dt);
            float push = (off - gp[3]) * Math.min(1f, dt*4f);
            x += (gp[0]-x)/off * push;
            z += (gp[2]-z)/off * push;
        }
        int n = track.size();
        if (prev > n*0.8f && trackT < n*0.2f) lap++;
        enginePitch = speed / MAX_SPEED;
    }

    static float wrapAngle(float a) {
        while (a > (float)Math.PI) a -= 2f*(float)Math.PI;
        while (a < -(float)Math.PI) a += 2f*(float)Math.PI;
        return a;
    }

    float progress() { return lap * track.totalLength + trackT / track.size() * track.totalLength; }
}
