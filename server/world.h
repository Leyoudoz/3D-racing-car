// 权威赛车世界：位置/速度/朝向由服务端独占计算，客户端仅发送输入。
// 物理参数与客户端 Car.java 一致；输入钳制防作弊。
#ifndef FR_SERVER_WORLD_H
#define FR_SERVER_WORLD_H

#include "protocol.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace fr {

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float wrapAngle(float a) {
    const float twoPi = 6.283185307179586f;
    while (a > 3.141592653589793f) a -= twoPi;
    while (a < -3.141592653589793f) a += twoPi;
    return a;
}

// 圆形封闭赛道几何（服务端为权威，客户端只按 snapshot 的 trackIndex 渲染对应赛道）。
// 每条赛道半径随索引变化，保证 21 条各有差异且可复现。
class TrackGeometry {
public:
    explicit TrackGeometry(int index) : index_(clampInt(index, 0, frproto::kTrackCount - 1)) {
        radius_ = 60.0f + (index_ % 7) * 12.0f;        // 60..132 m
        width_ = 10.0f + (index_ % 4) * 2.0f;          // 10..16 m
        length_ = 2.0f * 3.14159265f * radius_;
    }
    int index() const { return index_; }
    float radius() const { return radius_; }
    float halfWidth() const { return width_ * 0.5f; }
    float length() const { return length_; }
    // 赛道中心线上参数化点：与客户端样条不同，但服务端只用它做圈数与越界判定。
    void pointAt(float t, float& x, float& z) const {
        float a = t / length_ * 6.283185307179586f;
        x = std::cos(a) * radius_;
        z = std::sin(a) * radius_;
    }
    float yawAt(float t) const {
        float x, z;
        pointAt(t, x, z);
        return std::atan2(x, z); // 与客户端一致：yaw 为 atan2(x,z)
    }
    // 把世界位置投影到赛道最近弧长参数（粗采样 + 局部精修，足够权威判定）。
    float project(float x, float z) const {
        const int kSamples = 128;
        float best = 0.0f, bestD2 = 1e30f;
        for (int i = 0; i < kSamples; ++i) {
            float t = length_ * i / kSamples;
            float px, pz;
            pointAt(t, px, pz);
            float d2 = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d2 < bestD2) { bestD2 = d2; best = t; }
        }
        // 局部三点精修。
        float step = length_ / kSamples;
        for (int i = -2; i <= 2; ++i) {
            float t = best + step * i * 0.25f;
            float px, pz;
            pointAt(t, px, pz);
            float d2 = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d2 < bestD2) { bestD2 = d2; best = t; }
        }
        if (best < 0.0f) best += length_;
        return std::fmod(best, length_);
    }
private:
    static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    int index_;
    float radius_, width_, length_;
};

// 服务端车辆状态：每 tick 权威推进。
struct CarState {
    uint32_t clientId = 0;
    uint8_t slot = 0;
    bool occupied = false;
    // 输入（已钳制）。
    float throttle = 0.0f;
    float brake = 0.0f;
    float steer = 0.0f;
    // 权威运动状态。
    float x = 0.0f, y = 0.0f, z = 0.0f;
    float yaw = 0.0f;
    float speed = 0.0f;
    float steerSmooth = 0.0f;
    float lateral = 0.0f;
    float trackT = 0.0f;
    int lap = 0;
    int place = 1;
    bool finished = false;
    float finishTime = 0.0f;
    uint32_t lastInputSeq = 0;
    double lastInputTime = 0.0;

    void resetOnGrid(int gridIndex, const TrackGeometry& track) {
        trackT = track.length() - 2.0f - gridIndex * 1.6f;
        if (trackT < 0.0f) trackT += track.length();
        float px, pz;
        track.pointAt(trackT, px, pz);
        yaw = track.yawAt(trackT);
        float dirX = std::sin(yaw), dirZ = std::cos(yaw);
        float side = (gridIndex % 2 == 0) ? -2.2f : 2.2f;
        x = px - dirZ * side;
        z = pz + dirX * side;
        y = 0.0f;
        speed = 0.0f;
        steerSmooth = 0.0f;
        lateral = 0.0f;
        lap = 0;
        place = gridIndex + 1;
        finished = false;
        finishTime = 0.0f;
        throttle = 0.0f;
        brake = 0.0f;
        steer = 0.0f;
        lastInputSeq = 0;
        lastInputTime = 0.0;
    }

    // 用单个 tick 的权威物理推进本车。dt 固定为 1/60。
    void step(float dt, const TrackGeometry& track, double now) {
        if (finished) {
            // 完赛后减速滑行，不再计入名次竞争。
            steerSmooth += (0.0f - steerSmooth) * std::min(1.0f, dt * frproto::kSteerSmooth);
            speed = std::max(0.0f, speed - frproto::kBrake * dt);
            move(dt);
            (void)now;
            return;
        }
        // 权威状态纵深防御：即使上游校验被绕过，物理层也不接受越界输入。
        steer = clampf(steer, -1.0f, 1.0f);
        throttle = clampf(throttle, 0.0f, 1.0f);
        brake = clampf(brake, 0.0f, 1.0f);
        steerSmooth += (steer - steerSmooth) * std::min(1.0f, dt * frproto::kSteerSmooth);
        float accel = throttle * frproto::kAccel - brake * frproto::kBrake
                    - speed * frproto::kDrag;
        speed = clampf(speed + accel * dt, 0.0f, frproto::kMaxSpeed);
        move(dt);

        // 圈数判定：参数从接近终点回绕到起点（前进方向），防止倒车刷圈。
        float prevT = trackT;
        float newT = track.project(x, z);
        float delta = newT - prevT;
        if (delta < -track.length() * 0.5f) delta += track.length();  // 正向回绕
        if (delta > track.length() * 0.5f) delta -= track.length();   // 反向跨越
        if (delta > 0.0f) trackT = newT;
        // 反向行驶不推进 trackT，也不计入圈数。
        if (yawHeadingMatchesTrack(track) && delta > 0.0f &&
            prevT > track.length() * 0.8f && trackT < track.length() * 0.2f) {
            lap++;
        }

        // 越界减速与推回，防止穿出赛道。
        float px, pz;
        track.pointAt(trackT, px, pz);
        float off = std::hypot(px - x, pz - z);
        if (off > track.halfWidth()) {
            speed *= std::pow(0.25f, dt);
            float push = (off - track.halfWidth()) * std::min(1.0f, dt * 4.0f);
            x += (px - x) / off * push;
            z += (pz - z) / off * push;
        }
        (void)now;
    }

    // 按赛道进度排名，已完赛者按完赛时间排前面。
    float progress(const TrackGeometry& track) const {
        return static_cast<float>(lap) * track.length() + trackT;
    }

private:
    void move(float dt) {
        float turnRate = (speed / 24.0f) * steerSmooth * frproto::kTurnFactor;
        yaw = wrapAngle(yaw + turnRate * dt);
        float slip = speed * steerSmooth * frproto::kLateralSlip;
        lateral += (slip - lateral) * std::min(1.0f, dt * 5.0f);
        float fx = std::sin(yaw), fz = std::cos(yaw);
        float rx = std::cos(yaw), rz = -std::sin(yaw);
        x += (fx * speed + rx * lateral) * dt;
        z += (fz * speed + rz * lateral) * dt;
    }
    // 判断车头朝向是否大致沿赛道前进方向，避免倒车刷圈。
    bool yawHeadingMatchesTrack(const TrackGeometry& track) const {
        float targetYaw = track.yawAt(trackT);
        return std::fabs(wrapAngle(yaw - targetYaw)) < 1.5707963f; // |diff| < pi/2
    }
};

// 单房间：固定 6 槽位，独立赛道与比赛计时。
class Room {
public:
    explicit Room(int id) : id_(id), track_(0) {
        for (int i = 0; i < frproto::kCarsPerRoom; ++i) cars_[i].slot = static_cast<uint8_t>(i);
    }

    int id() const { return id_; }
    int trackIndex() const { return track_.index(); }
    const TrackGeometry& track() const { return track_; }
    CarState& car(int slot) { return cars_[slot]; }
    const CarState& car(int slot) const { return cars_[slot]; }
    int occupiedCount() const {
        int n = 0;
        for (int i = 0; i < frproto::kCarsPerRoom; ++i) if (cars_[i].occupied) ++n;
        return n;
    }

    // 分配一个空槽位；满员返回 -1。赛道在首车加入时由该车的 trackIndex 固定。
    int join(uint32_t clientId, int requestedTrack, double now) {
        if (occupiedCount() == 0) track_ = TrackGeometry(requestedTrack);
        for (int i = 0; i < frproto::kCarsPerRoom; ++i) {
            if (!cars_[i].occupied) {
                cars_[i].occupied = true;
                cars_[i].clientId = clientId;
                cars_[i].resetOnGrid(i, track_);
                cars_[i].lastInputTime = now;
                return i;
            }
        }
        return -1;
    }

    // 按 clientId 找槽位；不属于本房间返回 -1。
    int findByClient(uint32_t clientId) const {
        for (int i = 0; i < frproto::kCarsPerRoom; ++i)
            if (cars_[i].occupied && cars_[i].clientId == clientId) return i;
        return -1;
    }

    // 断开：清空槽位并重新排位。
    void leave(uint32_t clientId) {
        int s = findByClient(clientId);
        if (s < 0) return;
        cars_[s].occupied = false;
        cars_[s].clientId = 0;
        cars_[s].lastInputSeq = 0;
        updatePlaces();
    }

    // 权威推进一帧并更新名次/完赛标记。
    void step(float dt, double now) {
        for (int i = 0; i < frproto::kCarsPerRoom; ++i) {
            if (!cars_[i].occupied) continue;
            cars_[i].step(dt, track_, now);
            if (!cars_[i].finished && cars_[i].lap >= frproto::kLapsToFinish) {
                cars_[i].finished = true;
                cars_[i].finishTime = static_cast<float>(now);
            }
        }
        updatePlaces();
    }

    bool allFinished() const {
        for (int i = 0; i < frproto::kCarsPerRoom; ++i)
            if (cars_[i].occupied && !cars_[i].finished) return false;
        return true;
    }

private:
    void updatePlaces() {
        // 名次：已完赛按 finishTime 升序在前；未完赛按 progress 降序。
        int order[frproto::kCarsPerRoom];
        int n = 0;
        for (int i = 0; i < frproto::kCarsPerRoom; ++i)
            if (cars_[i].occupied) order[n++] = i;
        for (int a = 1; a < n; ++a) {
            int key = order[a];
            int b = a - 1;
            while (b >= 0 && better(key, order[b])) {
                order[b + 1] = order[b];
                --b;
            }
            order[b + 1] = key;
        }
        for (int i = 0; i < n; ++i) cars_[order[i]].place = i + 1;
    }
    bool better(int a, int b) const {
        const CarState& ca = cars_[a];
        const CarState& cb = cars_[b];
        if (ca.finished && cb.finished) return ca.finishTime < cb.finishTime;
        if (ca.finished != cb.finished) return ca.finished;
        return ca.progress(track_) > cb.progress(track_);
    }

    int id_;
    TrackGeometry track_;
    CarState cars_[frproto::kCarsPerRoom];
};

} // namespace fr

#endif // FR_SERVER_WORLD_H
