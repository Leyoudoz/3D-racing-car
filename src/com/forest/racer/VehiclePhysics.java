package com.forest.racer;

/**
 * 真实车辆动力学（单轨/自行车模型 + Pacejka 魔术公式轮胎 + 载荷转移 + 摩擦圆）。
 *
 * 权威依据：
 * - Pacejka 魔术公式：Fy = D*Fz*sin(C*atan(B*a - E*(B*a - atan(B*a))))
 *   来源 NovaSolver Tire Dynamics Simulator（novasolver.jp/en/tools/tire-dynamics.html）
 * - 摩擦圆：合力 (Fx,Fy) 超过 mu*Fz 时按径向缩放（同来源）
 * - 单轨模型：前轮转角 delta 产生横摆力矩，质心侧偏角 beta
 * - 载荷转移：纵向 m*a_x*h/L，横向 m*a_y*h/T（稳态近似）
 *
 * 单位：SI（m, kg, N, rad, s）。所有中间量为线性、无符号约定混淆。
 * 坐标约定见 codedocument/COORDINATE_CONVENTION.md：
 *   forward=(sin yaw,0,cos yaw)，yaw 增加=左转，输入 steer 正=右转（yaw 减小）。
 */
final class VehiclePhysics {
    // 车辆参数（中置超跑级）
    final float mass = 1480f;          // kg
    final float wheelBase = 2.65f;     // m 轴距
    final float cgToFront = 1.25f;     // m 质心到前轴
    final float cgToRear = wheelBase - cgToFront;
    final float trackWidth = 1.65f;    // m 轮距
    final float cgHeight = 0.46f;      // m 质心高度
    final float yawInertia = 2100f;    // kg*m^2 横摆转动惯量
    final float wheelRadius = 0.345f;  // m
    final float gravity = 9.81f;

    // 轮胎 Pacejka 系数（前/后可不同以调转向特性）
    float Bf = 10f, Cf = 1.9f, Df = 1.05f, Ef = -0.5f;   // 前轮
    float Br = 11f, Cr = 1.9f, Dr = 1.10f, Er = -0.5f;   // 后轮（略偏转向不足，稳定）

    // 动力总成
    final float maxDriveForce = 12000f;   // N 最大驱动力
    final float maxBrakeForce = 16000f;   // N 最大制动力
    final float engineBrake = 350f;       // N 松油门引擎制动
    final float dragCoef = 0.38f;         // 空气阻力系数
    final float frontalArea = 2.1f;       // m^2 迎风面积
    final float airDensity = 1.225f;      // kg/m^3
    final float rollResist = 0.015f;      // 滚动阻力系数

    // 状态
    float speed = 0f;         // m/s 纵向车速（质心处）
    float vy = 0f;            // m/s 横向车速（质心侧偏）
    float yawRate = 0f;       // rad/s 横摆角速度
    float steerAngle = 0f;    // rad 前轮转角（正=右转对应 yaw 减小）
    float wheelRoll = 0f;     // rad 车轮滚动累计
    float longitudinalAccel = 0f;
    float lateralAccel = 0f;

    private static final float MAX_STEER_ANGLE = (float) Math.toRadians(34f);

    /** 更新车辆动力学。throttle/brake 0..1，steerInput -1..1（正=右转），dt 秒。 */
    void update(float dt, float throttle, float brake, float steerInput) {
        if (dt <= 0 || dt > 0.1f) dt = 0.05f;

        // 前轮转角：随速度衰减但保留足够转向力（游戏手感）。
        // 低速(0)最大 34°，中速(30m/s)约 12°，高速(70m/s)约 6°。
        float speedFactor = 1f / (1f + 0.0022f * speed * speed);
        float maxDeltaAtSpeed = MAX_STEER_ANGLE * Math.max(0.18f, speedFactor);
        float targetDelta = -steerInput * maxDeltaAtSpeed;
        steerAngle += (targetDelta - steerAngle) * Math.min(1f, dt * 8f);

        // 纵向力：驱动 / 制动 / 引擎制动 / 空气阻力 / 滚阻
        float driveF = throttle * maxDriveForce * (1f - 0.55f * Math.min(1f, speed / 78f));
        float brakeF = brake * maxBrakeForce;
        float airF = 0.5f * airDensity * dragCoef * frontalArea * speed * speed;
        float rollF = rollResist * mass * gravity;
        float fx = driveF - brakeF - (throttle < 0.05f ? engineBrake : 0f) - airF - rollF;
        // 不允许倒车（刹车到0即停）
        if (fx < 0 && speed < 0.5f) { fx = 0; speed = 0; }

        // 载荷转移（稳态近似）
        float axApprox = fx / mass;
        float fzTotal = mass * gravity;
        float frontLoad = fzTotal * cgToRear / wheelBase - mass * axApprox * cgHeight / wheelBase;
        float rearLoad = fzTotal * cgToFront / wheelBase + mass * axApprox * cgHeight / wheelBase;
        frontLoad = Math.max(1500f, Math.min(fzTotal - 1500f, frontLoad));
        rearLoad = Math.max(1500f, Math.min(fzTotal - 1500f, rearLoad));
        float fzFront = frontLoad / 2f;   // 单侧前轮
        float fzRear = rearLoad / 2f;     // 单侧后轮

        // 滑移角：前轮 alpha_f = delta - beta - (a*r)/v；后轮 alpha_r = -beta + (b*r)/v
        float v = Math.max(2.0f, speed);  // 低速保护，避免除零
        float beta = (speed > 0.5f) ? (float) Math.atan2(vy, v) : 0f;
        float alphaF = steerAngle - beta - (cgToFront * yawRate) / v;
        float alphaR = -beta + (cgToRear * yawRate) / v;
        alphaF = clamp(alphaF, -0.6f, 0.6f);
        alphaR = clamp(alphaR, -0.6f, 0.6f);

        // Pacejka 侧向力（单侧轮胎 ×2）
        float fyFront = 2f * pacejka(alphaF, Bf, Cf, Df, Ef, fzFront);
        float fyRear = 2f * pacejka(alphaR, Br, Cr, Dr, Er, fzRear);

        // 摩擦圆约束：横向力不能超过 mu*Fz - 已用纵向力份额
        float mu = 1.05f;
        fyFront = frictionCircle(fyFront, fx * frontLoad / fzTotal, mu * frontLoad, 2f * fzFront);
        fyRear = frictionCircle(fyRear, fx * rearLoad / fzTotal, mu * rearLoad, 2f * fzRear);

        // 横向动力学：m*(vy_dot + v*r) = Fyf*cos(delta) + Fyr
        float ay = (fyFront * (float) Math.cos(steerAngle) + fyRear) / mass - v * yawRate;
        vy += ay * dt;

        // 横摆动力学：I*r_dot = a*Fyf*cos(delta) - b*Fyr
        float yawAccel = (cgToFront * fyFront * (float) Math.cos(steerAngle) - cgToRear * fyRear) / yawInertia;
        yawRate += yawAccel * dt;
        // 横摆阻尼，防止数值发散
        yawRate *= (1f - 0.3f * dt);

        // 纵向积分
        speed += axApprox * dt;
        speed = Math.max(0f, Math.min(speed, 82f));

        longitudinalAccel = axApprox;
        lateralAccel = (fyFront + fyRear) / mass;
        wheelRoll += (speed / wheelRadius) * dt;
    }

    /** Pacejka 魔术公式：Fy = D*Fz*sin(C*atan(B*a - E*(B*a - atan(B*a)))) */
    private static float pacejka(float alpha, float B, float C, float D, float E, float fz) {
        float Ba = B * alpha;
        float curve = C * (float) Math.atan(Ba - E * (Ba - (float) Math.atan(Ba)));
        return D * fz * (float) Math.sin(curve);
    }

    /** 摩擦圆径向缩放：合力超限时按 mu*Fz 缩放横向力 */
    private static float frictionCircle(float fy, float fxShare, float muFz, float fzAxle) {
        float fxAbs = Math.abs(fxShare);
        float remaining = (float) Math.sqrt(Math.max(0f, muFz * muFz - fxAbs * fxAbs));
        float fyAbs = Math.abs(fy);
        if (fyAbs > remaining && fyAbs > 0.001f) {
            return Math.signum(fy) * remaining;
        }
        return fy;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 供车辆世界坐标积分使用：返回本帧质心速度向量（车体坐标 → 世界由调用方旋转）。 */
    float forwardSpeed() { return speed; }
    float lateralSpeed() { return vy; }
    float yawRate() { return yawRate; }
}
