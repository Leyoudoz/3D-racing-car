// 森林赛车 UDP 网络协议定义（小端紧凑二进制）。
// 本文件只描述线上字节格式，不含业务逻辑，供服务端与文档共用。
#ifndef FR_SERVER_PROTOCOL_H
#define FR_SERVER_PROTOCOL_H

#include <cstdint>
#include <cstring>
#include <vector>

namespace frproto {

// 协议版本，客户端 HELLO 必须携带匹配值，否则服务端拒绝并记录 WARN。
constexpr uint32_t kProtocolVersion = 1;
// 单个 UDP 数据报最大字节数，超过即丢弃（防资源耗尽）。
constexpr size_t kMaxDatagram = 1500;
// 规模上限：房间数、每房车数、赛道数。
constexpr int kMaxRooms = 8;
constexpr int kCarsPerRoom = 6;
constexpr int kTrackCount = 21;

// 服务端固定 tick 频率与物理约束（与客户端 Car.java 保持一致）。
constexpr int kTickHz = 60;
constexpr float kTickDt = 1.0f / 60.0f;
constexpr float kMaxSpeed = 68.0f;        // m/s
constexpr float kAccel = 22.0f;           // m/s^2
constexpr float kBrake = 38.0f;           // m/s^2
constexpr float kDrag = 0.16f;            // 1/s
constexpr float kSteerSmooth = 9.0f;      // 1/s
constexpr float kTurnFactor = 1.85f;
constexpr float kLateralSlip = 0.09f;
constexpr int kLapsToFinish = 3;

// RAW 数据报首字节消息类型。
enum MsgType : uint8_t {
    HELLO       = 0x01, // 客户端 -> 服务端：申请 clientId
    JOIN        = 0x02, // 客户端 -> 服务端：加入房间
    INPUT       = 0x03, // 客户端 -> 服务端：驾驶输入
    HELLO_ACK   = 0x81, // 服务端 -> 客户端：返回 clientId 与服务器毫秒时间
    SNAPSHOT    = 0x82, // 服务端 -> 客户端：每 tick 广播的全部车辆状态
    JOIN_ACK    = 0x83, // 服务端 -> 客户端：JOIN 结果，slot=255 表示拒绝
};

// 各消息固定字节数（SNAPSHOT 为头部 + N*CAR_RECORD）。
constexpr size_t kHelloSize = 5;      // type(1) + version(4)
constexpr size_t kJoinSize = 9;       // type(1) + clientId(4) + room(2) + track(1) + reserved(1)
constexpr size_t kInputSize = 12;     // type(1) + clientId(4) + seq(4) + throttle(1) + brake(1) + steer(1)
constexpr size_t kHelloAckSize = 9;   // type(1) + clientId(4) + timeMs(4)
constexpr size_t kJoinAckSize = 8;    // type(1) + clientId(4) + slot(1) + room(2)
// type(1) + tick(4) + timeMs(4) + yourClientId(4) + yourSlot(1) + numCars(1) + room(2) + track(1) + ackSeq(4)
constexpr size_t kSnapshotHeader = 22;
// CAR 记录字段顺序（34 字节）：
//   clientId u32, slot u8, x f32, y f32, z f32, yaw f32, speed f32,
//   place u8, trackIndex u8, lap u8, finished u8, reserved u8, throttle u8, brake u8, steer i8, reserved u8
constexpr size_t kCarRecord = 34;

// 小端写入助手：全部操作带边界检查，越界时静默截断并返回 false。
class Writer {
public:
    explicit Writer(std::vector<uint8_t>& out) : out_(out) {}
    void u8(uint8_t v) { out_.push_back(v); }
    void i8(int8_t v) { out_.push_back(static_cast<uint8_t>(v)); }
    void u16(uint16_t v) {
        out_.push_back(static_cast<uint8_t>(v));
        out_.push_back(static_cast<uint8_t>(v >> 8));
    }
    void u32(uint32_t v) {
        out_.push_back(static_cast<uint8_t>(v));
        out_.push_back(static_cast<uint8_t>(v >> 8));
        out_.push_back(static_cast<uint8_t>(v >> 16));
        out_.push_back(static_cast<uint8_t>(v >> 24));
    }
    void f32(float v) {
        uint32_t bits;
        std::memcpy(&bits, &v, sizeof(bits));
        u32(bits);
    }
private:
    std::vector<uint8_t>& out_;
};

// 小端读取助手：越界读取返回 0 并置 ok=false。
class Reader {
public:
    Reader(const uint8_t* data, size_t len) : data_(data), len_(len) {}
    bool ok() const { return ok_; }
    uint8_t u8() {
        if (pos_ >= len_) { ok_ = false; return 0; }
        return data_[pos_++];
    }
    int8_t i8() { return static_cast<int8_t>(u8()); }
    uint16_t u16() {
        uint16_t a = u8(), b = u8();
        return static_cast<uint16_t>(a | (b << 8));
    }
    uint32_t u32() {
        uint32_t a = u8(), b = u8(), c = u8(), d = u8();
        return a | (b << 8) | (c << 16) | (d << 24);
    }
    float f32() {
        uint32_t bits = u32();
        float v;
        std::memcpy(&v, &bits, sizeof(v));
        return v;
    }
    size_t remaining() const { return len_ - pos_; }
private:
    const uint8_t* data_;
    size_t len_;
    size_t pos_ = 0;
    bool ok_ = true;
};

} // namespace frproto

#endif // FR_SERVER_PROTOCOL_H
