// 森林赛车权威服务端入口：UDP + 固定 60Hz tick + 房间/物理/比赛/广播。
// 编译: g++ -std=c++17 -O2 -static-libstdc++ -static-libgcc -pthread -o forest_racer_server main.cpp
// 运行: ./forest_racer_server [--port 7777] [--min-level INFO|DEBUG|...] [--no-color]
#include "protocol.h"
#include "logger.h"
#include "world.h"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iostream>
#include <netinet/in.h>
#include <string>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {

volatile sig_atomic_t gRunning = 1;
void onSignal(int) { gRunning = 0; }

std::string addrKey(const sockaddr_in& addr) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%08x:%u",
                  static_cast<unsigned>(ntohl(addr.sin_addr.s_addr)),
                  static_cast<unsigned>(ntohs(addr.sin_port)));
    return std::string(buf);
}

uint64_t nowMs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
}

double nowSec() {
    return std::chrono::duration<double>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string hexId(uint32_t id) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%08x", id);
    return std::string(buf);
}

class Server {
public:
    Server(int port, frlog::Logger& logger)
        : port_(port), logger_(logger), sock_(-1), nextClientId_(1) {
        for (int i = 0; i < frproto::kMaxRooms; ++i) rooms_[i] = new fr::Room(i);
    }
    ~Server() {
        for (int i = 0; i < frproto::kMaxRooms; ++i) delete rooms_[i];
        if (sock_ >= 0) close(sock_);
    }

    bool bind() {
        sock_ = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock_ < 0) {
            logError("socket", "socket creation failed", std::strerror(errno));
            return false;
        }
        int one = 1;
        setsockopt(sock_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        sockaddr_in addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = htons(static_cast<uint16_t>(port_));
        if (::bind(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
            logError("bind", "udp bind failed", std::strerror(errno));
            return false;
        }
        logger_.simple(frlog::INFO, "server", "startup",
                       "Forest racer authoritative server started",
                       {"port=" + std::to_string(port_),
                        "tick_hz=" + std::to_string(frproto::kTickHz),
                        "max_rooms=" + std::to_string(frproto::kMaxRooms),
                        "cars_per_room=" + std::to_string(frproto::kCarsPerRoom),
                        "protocol=" + std::to_string(frproto::kProtocolVersion)});
        return true;
    }

    void run() {
        // 用 select 等待可读，超时即推进物理 tick；固定 60Hz 用单调时钟补偿。
        const auto tickDur = std::chrono::microseconds(1000000 / frproto::kTickHz);
        auto nextTick = std::chrono::steady_clock::now();
        uint64_t tick = 0;
        while (gRunning) {
            auto now = std::chrono::steady_clock::now();
            if (now >= nextTick) {
                processTick(tick++, nowMs());
                nextTick += tickDur;
                if (nextTick < now) nextTick = now + tickDur; // 落后过多则跳过积压
                continue;
            }
            auto wait = std::chrono::duration_cast<std::chrono::microseconds>(nextTick - now);
            fd_set readfds;
            FD_ZERO(&readfds);
            FD_SET(sock_, &readfds);
            timeval tv;
            tv.tv_sec = static_cast<long>(wait.count() / 1000000);
            tv.tv_usec = static_cast<long>(wait.count() % 1000000);
            int r = select(sock_ + 1, &readfds, NULL, NULL, &tv);
            if (r > 0 && FD_ISSET(sock_, &readfds)) receiveOne();
            else if (r < 0 && errno != EINTR) {
                logError("select", "select failed", std::strerror(errno));
                break;
            }
        }
        logger_.simple(frlog::INFO, "server", "shutdown",
                       "Forest racer authoritative server stopping",
                       {"tick=" + std::to_string(tick),
                        "clients=" + std::to_string(clients_.size())});
    }

private:
    struct Client {
        uint32_t id = 0;
        sockaddr_in addr;
        int room = -1;
        int slot = -1;
        double lastSeen = 0.0;
        uint32_t inputPackets = 0;
    };

    void logError(const std::string& module, const std::string& summary,
                  const std::string& detail) {
        frlog::Entry e;
        e.level = frlog::ERROR;
        e.module = module;
        e.traceId = "server-error";
        e.summary = summary;
        e.errorType = "SystemError";
        e.errorMessage = detail;
        e.errorStack = "server/main.cpp";
        e.fields.push_back(std::make_pair("detail", detail));
        logger_.log(e);
    }

    void receiveOne() {
        uint8_t buf[frproto::kMaxDatagram];
        sockaddr_in from;
        socklen_t fromLen = sizeof(from);
        ssize_t n = recvfrom(sock_, buf, sizeof(buf), 0,
                             reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) return;
        std::string key = addrKey(from);
        double now = nowSec();
        auto it = clients_.find(key);
        if (it == clients_.end()) {
            // 未握手地址只允许 HELLO。
            if (n >= static_cast<ssize_t>(frproto::kHelloSize) && buf[0] == frproto::HELLO) {
                handleHello(key, from, buf, static_cast<size_t>(n), now);
            } else {
                logger_.simple(frlog::WARN, "net", "drop-unknown-source",
                               "Dropped datagram from unregistered source",
                               {"src=" + key, "bytes=" + std::to_string(n)});
                ++droppedPackets_;
            }
            return;
        }
        Client& c = it->second;
        c.lastSeen = now;
        frproto::Reader r(buf, static_cast<size_t>(n));
        uint8_t type = r.u8();
        switch (type) {
            case frproto::HELLO: handleHello(key, from, buf, static_cast<size_t>(n), now); break;
            case frproto::JOIN:  handleJoin(c, from, buf, static_cast<size_t>(n), now); break;
            case frproto::INPUT: handleInput(c, buf, static_cast<size_t>(n), now); break;
            default:
                logger_.simple(frlog::WARN, "net", "drop-unknown-type",
                               "Dropped datagram with unknown message type",
                               {"src=" + key, "type=0x" + hexByte(type)});
                ++droppedPackets_;
                break;
        }
    }

    static std::string hexByte(uint8_t v) {
        char buf[4];
        std::snprintf(buf, sizeof(buf), "%02x", v);
        return std::string(buf);
    }

    void handleHello(const std::string& key, const sockaddr_in& from,
                     const uint8_t* buf, size_t len, double now) {
        frproto::Reader r(buf, len);
        uint8_t type = r.u8();
        uint32_t version = r.u32();
        if (!r.ok() || version != frproto::kProtocolVersion) {
            logger_.simple(frlog::WARN, "net", "hello-rejected",
                           "Rejected HELLO with invalid version",
                           {"src=" + key, "version=" + std::to_string(version),
                            "expected=" + std::to_string(frproto::kProtocolVersion)});
            return;
        }
        (void)type;
        uint32_t id = nextClientId_++;
        Client c;
        c.id = id;
        c.addr = from;
        c.lastSeen = now;
        clients_[key] = c;
        std::vector<uint8_t> out;
        frproto::Writer w(out);
        w.u8(frproto::HELLO_ACK);
        w.u32(id);
        w.u32(static_cast<uint32_t>(nowMs() & 0xFFFFFFFFu));
        sendRaw(from, out);
        logger_.simple(frlog::INFO, "net", "client-hello",
                       "Assigned client id",
                       {"src=" + key, "client_id=" + hexId(id)});
    }

    void handleJoin(Client& c, const sockaddr_in& from,
                    const uint8_t* buf, size_t len, double now) {
        frproto::Reader r(buf, len);
        r.u8(); // type
        uint32_t clientId = r.u32();
        uint16_t roomId = r.u16();
        uint8_t track = r.u8();
        r.u8(); // reserved
        if (!r.ok()) {
            logger_.simple(frlog::WARN, "net", "malformed-join",
                           "Dropped malformed JOIN", {"client_id=" + hexId(c.id)});
            return;
        }
        // 防伪造：包里的 clientId 必须等于地址绑定的 id。
        if (clientId != c.id) {
            logger_.simple(frlog::WARN, "security", "spoofed-join",
                           "Rejected JOIN with mismatched client id",
                           {"claimed=" + hexId(clientId), "bound=" + hexId(c.id)});
            return;
        }
        int slot = -1;
        std::vector<uint8_t> out;
        frproto::Writer w(out);
        if (roomId >= frproto::kMaxRooms || track >= frproto::kTrackCount) {
            logger_.simple(frlog::WARN, "room", "join-invalid",
                           "Rejected JOIN with out-of-range room or track",
                           {"client_id=" + hexId(c.id), "room=" + std::to_string(roomId),
                            "track=" + std::to_string(static_cast<int>(track))});
            slot = -1;
        } else {
            // 换房：先从旧房间移除。
            if (c.room >= 0) rooms_[c.room]->leave(c.id);
            slot = rooms_[roomId]->join(c.id, static_cast<int>(track), now);
            if (slot >= 0) {
                c.room = static_cast<int>(roomId);
                c.slot = slot;
                logger_.simple(frlog::INFO, "room", "client-joined",
                               "Client joined racing room",
                               {"client_id=" + hexId(c.id), "room=" + std::to_string(roomId),
                                "slot=" + std::to_string(slot),
                                "track=" + std::to_string(static_cast<int>(track))});
            } else {
                logger_.simple(frlog::WARN, "room", "room-full",
                               "Rejected JOIN because room is full",
                               {"client_id=" + hexId(c.id), "room=" + std::to_string(roomId)});
            }
        }
        w.u8(frproto::JOIN_ACK);
        w.u32(c.id);
        w.u8(slot < 0 ? 255 : static_cast<uint8_t>(slot));
        w.u16(roomId);
        sendRaw(from, out);
    }

    void handleInput(Client& c, const uint8_t* buf, size_t len, double now) {
        frproto::Reader r(buf, len);
        r.u8(); // type
        uint32_t clientId = r.u32();
        uint32_t seq = r.u32();
        uint8_t throttleRaw = r.u8();
        uint8_t brakeRaw = r.u8();
        int8_t steerRaw = r.i8();
        if (!r.ok()) return;
        if (clientId != c.id) {
            logger_.simple(frlog::WARN, "security", "spoofed-input",
                           "Rejected INPUT with mismatched client id",
                           {"claimed=" + hexId(clientId), "bound=" + hexId(c.id)});
            return;
        }
        if (c.room < 0 || c.slot < 0) return;
        fr::CarState& car = rooms_[c.room]->car(c.slot);
        if (!car.occupied || car.clientId != c.id) return;
        // 顺序号单调，旧包丢弃（防重放）。
        if (seq < car.lastInputSeq) return;
        car.lastInputSeq = seq;
        car.lastInputTime = now;
        // 输入钳制：throttle/brake 0..255 映射到 0..1，steer -127..127 映射到 -1..1。
        car.throttle = static_cast<float>(throttleRaw) / 255.0f;
        car.brake = static_cast<float>(brakeRaw) / 255.0f;
        car.steer = fr::clampf(static_cast<float>(steerRaw) / 127.0f, -1.0f, 1.0f);
        ++c.inputPackets;
    }

    void processTick(uint64_t tick, uint64_t timeMs) {
        for (int i = 0; i < frproto::kMaxRooms; ++i) {
            fr::Room& room = *rooms_[i];
            if (room.occupiedCount() == 0) continue;
            room.step(frproto::kTickDt, nowSec());
            broadcastSnapshot(room, tick, timeMs);
        }
        // 每 60 tick（1 秒）输出一次健康统计。
        if (tick % frproto::kTickHz == 0 && tick > 0) {
            size_t occupied = 0;
            for (int i = 0; i < frproto::kMaxRooms; ++i) occupied += rooms_[i]->occupiedCount();
            logger_.simple(frlog::DEBUG, "server", "tick-health",
                           "Server tick health report",
                           {"tick=" + std::to_string(tick),
                            "clients=" + std::to_string(clients_.size()),
                            "cars=" + std::to_string(occupied),
                            "dropped=" + std::to_string(droppedPackets_),
                            "tick_hz=" + std::to_string(frproto::kTickHz)});
        }
    }

    void broadcastSnapshot(const fr::Room& room, uint64_t tick, uint64_t timeMs) {
        for (std::unordered_map<std::string, Client>::iterator it = clients_.begin();
             it != clients_.end(); ++it) {
            const Client& c = it->second;
            if (c.room != room.id() || c.slot < 0) continue;
            std::vector<uint8_t> out;
            buildSnapshot(room, c, tick, timeMs, out);
            sendRaw(c.addr, out);
        }
    }

    // 构造 SNAPSHOT：头部 + 每个占位车辆 32 字节记录。
    static void buildSnapshot(const fr::Room& room, const Client& viewer,
                              uint64_t tick, uint64_t timeMs, std::vector<uint8_t>& out) {
        int count = room.occupiedCount();
        out.reserve(frproto::kSnapshotHeader + static_cast<size_t>(count) * frproto::kCarRecord);
        frproto::Writer w(out);
        w.u8(frproto::SNAPSHOT);
        w.u32(static_cast<uint32_t>(tick));
        w.u32(static_cast<uint32_t>(timeMs & 0xFFFFFFFFu));
        w.u32(viewer.id);
        w.u8(viewer.slot < 0 ? 255 : static_cast<uint8_t>(viewer.slot));
        w.u8(static_cast<uint8_t>(count));
        w.u16(static_cast<uint16_t>(room.id()));
        w.u8(static_cast<uint8_t>(room.trackIndex()));
        // 和解字段：服务端已处理的该客户端最新输入序号。
        w.u32(viewer.slot >= 0 ? room.car(viewer.slot).lastInputSeq : 0u);
        for (int i = 0; i < frproto::kCarsPerRoom; ++i) {
            const fr::CarState& car = room.car(i);
            if (!car.occupied) continue;
            w.u32(car.clientId);
            w.u8(car.slot);
            w.f32(car.x);
            w.f32(car.y);
            w.f32(car.z);
            w.f32(car.yaw);
            w.f32(car.speed);
            w.u8(static_cast<uint8_t>(car.place));
            w.u8(static_cast<uint8_t>(room.trackIndex()));
            w.u8(static_cast<uint8_t>(std::min(car.lap, 255)));
            w.u8(car.finished ? 1 : 0);
            w.u8(0); // reserved
            w.u8(static_cast<uint8_t>(fr::clampf(car.throttle, 0.0f, 1.0f) * 255.0f));
            w.u8(static_cast<uint8_t>(fr::clampf(car.brake, 0.0f, 1.0f) * 255.0f));
            w.i8(static_cast<int8_t>(fr::clampf(car.steer, -1.0f, 1.0f) * 127.0f));
            w.u8(0); // reserved
        }
    }

    void sendRaw(const sockaddr_in& to, const std::vector<uint8_t>& data) {
        ssize_t sent = sendto(sock_, data.data(), data.size(), 0,
                              reinterpret_cast<const sockaddr*>(&to), sizeof(to));
        if (sent < 0) {
            logger_.simple(frlog::WARN, "net", "send-failed",
                           "Failed to send datagram",
                           {"bytes=" + std::to_string(data.size()),
                            "error=" + std::string(std::strerror(errno))});
        }
    }

    int port_;
    frlog::Logger& logger_;
    int sock_;
    uint32_t nextClientId_;
    fr::Room* rooms_[frproto::kMaxRooms];
    std::unordered_map<std::string, Client> clients_;
    uint64_t droppedPackets_ = 0;
};

frlog::Level parseLevel(const std::string& s) {
    if (s == "TRACE") return frlog::TRACE;
    if (s == "DEBUG") return frlog::DEBUG;
    if (s == "INFO")  return frlog::INFO;
    if (s == "WARN")  return frlog::WARN;
    if (s == "ERROR") return frlog::ERROR;
    if (s == "CRITICAL") return frlog::CRITICAL;
    return frlog::INFO;
}

} // namespace

int main(int argc, char** argv) {
    int port = 7777;
    frlog::Level minLevel = frlog::INFO;
    bool color = true;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--port" && i + 1 < argc) {
            port = std::atoi(argv[++i]);
            if (port < 1 || port > 65535) {
                std::fprintf(stderr, "invalid port: %d\n", port);
                return 2;
            }
        } else if (arg == "--min-level" && i + 1 < argc) {
            minLevel = parseLevel(argv[++i]);
        } else if (arg == "--no-color") {
            color = false;
        } else if (arg == "--help") {
            std::printf("usage: forest_racer_server [--port N] [--min-level LEVEL] [--no-color]\n");
            return 0;
        } else {
            std::fprintf(stderr, "unknown argument: %s\n", arg.c_str());
            return 2;
        }
    }
    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);
    frlog::Logger logger(minLevel, color, static_cast<int>(getpid()));
    Server server(port, logger);
    if (!server.bind()) return 1;
    server.run();
    return 0;
}
