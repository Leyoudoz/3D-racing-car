# 森林赛车权威服务端与客户端网络设计

## 1. 概述

服务端为 C++17 权威 UDP 赛车服务器，固定 60Hz tick，最多 8 个房间，每房最多 6 辆车。客户端为纯 Java（仅 JDK，无第三方库），负责连接、输入发送、快照接收、客户端预测与服务端和解、其他车插值显示、断线重连。

- 权威原则：位置、速度、朝向、圈数、名次全部由服务端计算；客户端只发送输入并展示快照。
- 单位：坐标 m，速度 m/s，角度 rad，时间 ms。
- 时间基线：2026-11-26 16:30 CST（本机 `date` 实测）。

## 2. 写作用域

本模块只写以下路径，未触碰 lead 负责的 `src/com/forest/racer/` 下其他 .java、build.sh、assets、AndroidManifest.xml：

- `server/`：protocol.h、logger.h、world.h、main.cpp、Makefile、build_server.sh
- `src/com/forest/racer/net/`：Protocol.java、NetClient.java
- `codedocument/SERVER_DESIGN.md`
- `tests/server_test.cpp`、`tests/test_net.sh`

## 3. 协议表（小端紧凑二进制）

### 3.1 消息类型

| 方向 | 类型 | 值 | 长度(字节) | 说明 |
|---|---|---|---|---|
| C -> S | HELLO | 0x01 | 5 | 申请 clientId |
| C -> S | JOIN | 0x02 | 9 | 加入房间 |
| C -> S | INPUT | 0x03 | 12 | 驾驶输入 |
| S -> C | HELLO_ACK | 0x81 | 9 | 返回 clientId 与服务器毫秒时间 |
| S -> C | JOIN_ACK | 0x83 | 8 | 加入结果，slot=255 表示拒绝 |
| S -> C | SNAPSHOT | 0x82 | 18 + 34*N | 每 tick 广播，N 为房间车数 |

### 3.2 包结构

| 消息 | 偏移 | 字段 | 类型 | 说明 |
|---|---|---|---|---|
| HELLO | 0 | type | u8 | 0x01 |
| HELLO | 1 | version | u32 | 固定 1，不符拒绝并记 WARN |
| JOIN | 0 | type | u8 | 0x02 |
| JOIN | 1 | clientId | u32 | 必须等于地址绑定的 id，防伪造 |
| JOIN | 5 | room | u16 | 0..7 |
| JOIN | 7 | track | u8 | 0..20 |
| JOIN | 8 | reserved | u8 | 0 |
| INPUT | 0 | type | u8 | 0x03 |
| INPUT | 1 | clientId | u32 | 防伪造校验 |
| INPUT | 5 | seq | u32 | 输入序号，旧包丢弃防重放 |
| INPUT | 9 | throttle | u8 | 0..255 映射 0..1 |
| INPUT | 10 | brake | u8 | 0..255 映射 0..1 |
| INPUT | 11 | steer | i8 | -127..127 映射 -1..1 |
| HELLO_ACK | 0 | type | u8 | 0x81 |
| HELLO_ACK | 1 | clientId | u32 | 服务端分配 |
| HELLO_ACK | 5 | timeMs | u32 | 服务端毫秒时间低 32 位 |
| JOIN_ACK | 0 | type | u8 | 0x83 |
| JOIN_ACK | 1 | clientId | u32 | 回显 |
| JOIN_ACK | 5 | slot | u8 | 0..5 成功，255 拒绝 |
| JOIN_ACK | 6 | room | u16 | 房间号 |
| SNAPSHOT 头 | 0 | type | u8 | 0x82 |
| SNAPSHOT 头 | 1 | tick | u32 | 服务端 tick 计数 |
| SNAPSHOT 头 | 5 | timeMs | u32 | 服务端毫秒时间低 32 位 |
| SNAPSHOT 头 | 9 | yourClientId | u32 | 接收方 id |
| SNAPSHOT 头 | 13 | yourSlot | u8 | 接收方槽位，255 无效 |
| SNAPSHOT 头 | 14 | numCars | u8 | 本帧车辆数 1..6 |
| SNAPSHOT 头 | 15 | room | u16 | 房间号 |
| SNAPSHOT 头 | 17 | track | u8 | 赛道索引 |
| SNAPSHOT 头 | 18 | ackSeq | u32 | 服务端已处理的该客户端输入序号，用于和解 |
| CAR 记录 | 0 | clientId | u32 | 车辆 id |
| CAR 记录 | 4 | slot | u8 | 槽位 |
| CAR 记录 | 5 | x | f32 | 位置 m |
| CAR 记录 | 9 | y | f32 | 位置 m |
| CAR 记录 | 13 | z | f32 | 位置 m |
| CAR 记录 | 17 | yaw | f32 | 朝向 rad |
| CAR 记录 | 21 | speed | f32 | 速度 m/s |
| CAR 记录 | 25 | place | u8 | 名次 1..6 |
| CAR 记录 | 26 | trackIndex | u8 | 赛道索引 |
| CAR 记录 | 27 | lap | u8 | 圈数 |
| CAR 记录 | 28 | finished | u8 | 是否完赛 |
| CAR 记录 | 29 | reserved | u8 | 0 |
| CAR 记录 | 30 | throttle | u8 | 0..255 |
| CAR 记录 | 31 | brake | u8 | 0..255 |
| CAR 记录 | 32 | steer | i8 | -127..127 |
| CAR 记录 | 33 | reserved | u8 | 0 |

单条 UDP 数据报上限 1500 字节，超限丢弃。SNAPSHOT 最大 18 + 34*6 = 222 字节。

## 4. 服务端物理与比赛规则

- tick 固定 60Hz（dt = 1/60 s），用 steady_clock 补偿，落后过多跳过积压。
- 物理参数与客户端 `Car.java` 对齐：最高速 68 m/s，加速度 22 m/s²，刹车 38 m/s²，阻力系数 0.16 /s，转向平滑系数 9 /s，转向系数 1.85，侧滑系数 0.09。
- 输入校验：throttle/brake 限 [0,1]，steer 限 [-1,1]，物理层再做一次防御性钳制（即使上游校验被绕过也不会越界）。
- 速度钳制 [0, 68] m/s，刹车不产生负速度。
- 越界减速并推回赛道，防穿模。
- 圈数按赛道弧长参数正向回绕判定，且要求车头朝向与赛道前进方向夹角小于 pi/2，防倒车刷圈。
- 3 圈完赛；名次：已完赛按完赛时间升序在前，未完赛按进度降序。
- 安全：clientId 与源地址绑定，HELLO 前丢弃非握手包，INPUT/JOIN 校验 id 防伪造，seq 单调防重放，房间/赛道越界拒绝，未知消息类型记 WARN 并丢弃。

## 5. 编译与运行命令

### 5.1 服务端

```bash
cd server
./build_server.sh
# 等价于：g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic main.cpp -o forest_racer_server -static-libstdc++ -static-libgcc -pthread
./forest_racer_server --port 7777
# 可选：--min-level TRACE|DEBUG|INFO|WARN|ERROR|CRITICAL  --no-color
```

`ldd` 实测仅依赖 libm 与 libc（libstdc++/libgcc 已静态链接）。

### 5.2 客户端（纯 JDK）

```bash
javac src/com/forest/racer/net/Protocol.java src/com/forest/racer/net/NetClient.java
# 或指定输出目录：javac -d out src/com/forest/racer/net/*.java
```

## 6. 客户端接口（给 lead 集成）

```java
NetClient client = new NetClient();
client.config().room = 0;          // connect 前设置
client.config().trackIndex = 0;
client.config().autoReconnect = true;
boolean ok = client.connect("127.0.0.1", 7777);
client.sendInput(throttle, brake, steer);   // 各参数 0..1 / 0..1 / -1..1
List<Protocol.Snapshot> snaps = client.getSnapshots();   // 取出并清空队列
NetClient.PredictedState me = client.getPredictedState();// 玩家车渲染用预测状态
Protocol.CarState other = client.getInterpolated(clientId);// 其他车插值显示
client.disconnect();
```

其他公开方法：`isConnected()`、`clientId()`、`lastError()`、`droppedSnapshots()`、`getRemoteState(int clientId)`。

- `Protocol.Snapshot`：tick, timeMs, yourClientId, yourSlot, room, trackIndex, ackSeq, cars (`List<CarState>`), `you()`。
- `Protocol.CarState`：clientId, slot, x, y, z, yaw, speed, place, trackIndex, lap, finished, throttle, brake, steer。
- 和解机制：每次 `sendInput` 记录输入历史并本地预测积分；收到 SNAPSHOT 后用 `ackSeq` 丢弃已确认输入，以服务端权威状态为基准重放未确认输入。

## 7. 测试方法

### 7.1 服务端单元测试

```bash
g++ -std=c++17 -O2 -Wall -Wextra -Iserver tests/server_test.cpp -o /tmp/fr_server_test
/tmp/fr_server_test
```

覆盖：小端编解码往返、越界读取检测、速度/转向钳制、刹车不为负、房间满员拒绝、按进度排名、完赛排名优先、3 圈完赛、协议常量自洽。

### 7.2 端到端网络测试

```bash
./tests/test_net.sh          # 默认端口 7788
./tests/test_net.sh 7801     # 指定端口
```

脚本启动服务端，用 Python UDP 客户端走完 HELLO -> HELLO_ACK -> JOIN -> JOIN_ACK -> INPUT -> SNAPSHOT，解析并校验字段，通过打印 `NET_TEST_PASSED`。

## 8. 验证证据（实测输出）

时间基线：2026-11-26 16:30 CST 起测试。

### 8.1 编译

```text
$ ./server/build_server.sh
Building forest_racer_server with g++ (g++ (Ubuntu 11.4.0-1ubuntu1~22.04.3) 11.4.0)
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic main.cpp -o forest_racer_server -static-libstdc++ -static-libgcc -pthread
OK: .../server/forest_racer_server

$ ldd server/forest_racer_server
    linux-vdso.so.1
    libm.so.6 => /lib/x86_64-linux-gnu/libm.so.6
    libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6
    /lib64/ld-linux-x86-64.so.2
```

零 warning（`-Wall -Wextra -Wpedantic` 下 `grep -c 'warning:'` = 0）。

### 8.2 单元测试

```text
$ /tmp/fr_server_test
...
PASS_COUNT=23 FAIL_COUNT=0
ALL_TESTS_PASSED
```

### 8.3 端到端网络测试

```text
$ ./tests/test_net.sh 7801
HELLO_ACK client_id=0x00000001 srv_ms=386653718
JOIN_ACK client_id=0x00000001 slot=0 room=2
SNAPSHOT tick=35 time_ms=386653763 num_cars=1 room=2 track=5
OWN_CAR x=119.98 y=0.00 z=-4.20 yaw=1.59 speed=1.10 place=1 lap=0
NET_TEST_PASSED frames=3
```

`speed=1.10 m/s` 证明满油门 INPUT 真实驱动权威物理（不是静态快照）。

### 8.4 Java 客户端联调

```text
$ javac -Xlint:all -d /tmp/fr_java_out src/com/forest/racer/net/Protocol.java src/com/forest/racer/net/NetClient.java
JAVAC_OK（零 warning）

$ java NetSmoke <port>    # 临时冒烟程序，连真服务端，60 帧满油门输入
CONNECTED clientId=0x1
SNAPSHOTS=77 LAST_ACK=60 SERVER_SPEED=15.432834 PREDICT_SPEED=15.432834
PREDICT_X=70.29578 ROOM=4 TRACK=7
JAVA_NET_SMOKE_PASSED
```

服务端速度等于预测速度，证明和解生效；ackSeq=60 与发送 60 帧输入一致。

### 8.5 日志格式样例（实际输出，INFO 绿/WARN 黄/ERROR 红，终端 ANSI）

```text
[2026-11-26 16:38:44 INFO] Forest racer authoritative server started
- timestamp: 2026-11-26T16:38:44.853+08:00
- level: INFO
- trace_id: startup
- service: forest-racer-server
- module: server
- pid: 23242
- message: Forest racer authoritative server started
- port: 7788
- tick_hz: 60
- max_rooms: 8
- cars_per_room: 6
- protocol: 1
```

每条日志多行，首行 `[时间 级别] 概要`，后续每行 `- 字段: 值`，全英文，字段无空值占位；浮点统一在调用处按两位小数并带单位（如 `%.2f m/s`）。TRACE/DEBUG 灰、INFO 绿、WARN 黄、ERROR 红、CRITICAL 红底白字加粗。

## 9. 已知边界与注意事项

- 服务端赛道几何为圆形参数化，与客户端 21 条样条赛道形状不同；服务端只用它做权威物理、越界判定与圈数，客户端按 `trackIndex` 渲染自己版本的赛道。若需视觉轨迹完全一致，需后续统一赛道数据源。
- 明文 UDP，无加密与鉴权，面向局域网游戏场景；公网部署需另行加鉴权与加密。
- 每个客户端 socket 只占一个房间；换房会先从旧房间移除。
- 房间在首车加入时由该车的 track 参数固定，后续加入者以该赛道为准。
- `getSnapshots()` 会清空队列；若渲染帧率低于 60Hz，一次可取多帧，调用方按需只用最后一帧。
- 测试脚本使用 7788/7801 等非默认端口，避免与默认 7777 冲突。测试结束自动关闭服务端并清理临时日志。
