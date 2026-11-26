// 服务端单元测试：协议编解码、输入钳制、物理上限、比赛名次。
// 编译: g++ -std=c++17 -O2 -I../server ../tests/server_test.cpp -o /tmp/fr_server_test
// 运行: /tmp/fr_server_test ，全部断言通过打印 ALL_TESTS_PASSED 并返回 0。
#include "protocol.h"
#include "world.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

static int gPass = 0, gFail = 0;
#define CHECK(cond, name) do { \
    if (cond) { ++gPass; std::printf("PASS %s\n", name); } \
    else { ++gFail; std::printf("FAIL %s\n", name); } \
} while (0)

int main() {
    // 1. Writer/Reader 小端一致性。
    {
        std::vector<uint8_t> buf;
        frproto::Writer w(buf);
        w.u8(0x12);
        w.u16(0x3456);
        w.u32(0x789ABCDEu);
        w.f32(1.5f);
        CHECK(buf.size() == 11, "wire-size");
        CHECK(buf[0] == 0x12 && buf[1] == 0x56 && buf[2] == 0x34, "little-endian-u16");
        frproto::Reader r(buf.data(), buf.size());
        CHECK(r.u8() == 0x12 && r.u16() == 0x3456 && r.u32() == 0x789ABCDEu, "roundtrip-ints");
        CHECK(std::fabs(r.f32() - 1.5f) < 1e-6f, "roundtrip-float");
        CHECK(r.remaining() == 0 && r.ok(), "reader-complete");
    }
    // 2. Reader 越界置 ok=false。
    {
        uint8_t small[2] = {0x01, 0x02};
        frproto::Reader r(small, sizeof(small));
        r.u32();
        CHECK(!r.ok(), "reader-overrun-detected");
    }
    // 3. 输入钳制与速度上限：满油门 10 秒不得超过 kMaxSpeed。
    {
        fr::Room room(0);
        int slot = room.join(0x1001, 0, 0.0);
        CHECK(slot == 0, "room-join-slot-0");
        fr::CarState& car = room.car(slot);
        car.throttle = 10.0f; // 故意越界输入
        car.brake = -5.0f;
        car.steer = 50.0f;
        for (int i = 0; i < 600; ++i) room.step(frproto::kTickDt, i * frproto::kTickDt);
        CHECK(car.speed <= frproto::kMaxSpeed + 1e-3f, "speed-clamped-to-max");
        CHECK(std::fabs(car.steerSmooth) <= 1.0f + 1e-3f, "steer-clamped");
    }
    // 4. 刹车不产生负速度。
    {
        fr::Room room(1);
        int slot = room.join(0x2001, 1, 0.0);
        fr::CarState& car = room.car(slot);
        car.speed = 5.0f;
        car.throttle = 0.0f;
        car.brake = 1.0f;
        for (int i = 0; i < 120; ++i) room.step(frproto::kTickDt, i * frproto::kTickDt);
        CHECK(car.speed >= 0.0f && car.speed < 0.01f, "brake-no-negative-speed");
    }
    // 5. 房间满员拒绝。
    {
        fr::Room room(2);
        for (int i = 0; i < frproto::kCarsPerRoom; ++i)
            CHECK(room.join(0x3000 + i, 2, i) == i, "room-join-all-slots");
        CHECK(room.join(0x4001, 2, 7.0) == -1, "room-full-rejects");
    }
    // 6. 名次：人为设置进度，检查排序。
    {
        fr::Room room(3);
        room.join(0x5001, 3, 0.0);
        room.join(0x5002, 3, 0.0);
        fr::CarState& a = room.car(0);
        fr::CarState& b = room.car(1);
        a.trackT = 100.0f; b.trackT = 10.0f;
        room.step(frproto::kTickDt, 1.0);
        CHECK(a.place == 1 && b.place == 2, "place-by-progress");
        b.finished = true; b.finishTime = 1.0;
        room.step(frproto::kTickDt, 1.1);
        CHECK(b.place == 1 && a.place == 2, "finished-ranks-first");
    }
    // 7. 完赛判定：lap 达到 3 即 finished。
    {
        fr::Room room(4);
        int slot = room.join(0x6001, 4, 0.0);
        fr::CarState& car = room.car(slot);
        car.lap = frproto::kLapsToFinish;
        room.step(frproto::kTickDt, 2.0);
        CHECK(car.finished, "finish-at-3-laps");
        CHECK(room.allFinished(), "all-finished-true");
    }
    // 8. HELLO/JOIN/INPUT/SNAPSHOT 消息长度常量与布局自洽。
    {
        CHECK(frproto::kHelloSize == 5 && frproto::kJoinSize == 9 &&
              frproto::kInputSize == 12 && frproto::kHelloAckSize == 9 &&
              frproto::kJoinAckSize == 8, "fixed-message-sizes");
        CHECK(frproto::kSnapshotHeader == 22 && frproto::kCarRecord == 34, "snapshot-layout");
    }

    std::printf("PASS_COUNT=%d FAIL_COUNT=%d\n", gPass, gFail);
    if (gFail == 0) { std::printf("ALL_TESTS_PASSED\n"); return 0; }
    return 1;
}
