package com.forest.racer.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端网络：UDP 连接权威服务端，发送输入、接收快照、客户端预测 + 服务端和解。
 *
 * 给 lead 的集成接口：
 *   boolean connect(String host, int port)          连接并握手（HELLO + JOIN）
 *   void sendInput(float throttle, float brake, float steer)  发送一帧输入（非零即预测）
 *   List<Protocol.Snapshot> getSnapshots()           取出并清空自上次调用以来的快照队列
 *   void disconnect()                                断开、停线程、清理资源
 *
 * 额外可选接口：
 *   PredictedState getPredictedState()               取本机预测状态（渲染玩家车用）
 *   Protocol.CarState getInterpolated(int clientId)  取其他车的插值显示状态
 *   boolean isConnected() / int clientId() / String lastError()
 *   NetConfig config() 可调整房间、赛道、重连参数。
 *
 * 线程安全：接收线程、发送线程、调用方线程互不共享可变状态；队列与状态用并发容器/锁保护。
 * 纯 JDK，无第三方库；不依赖 com.forest.racer 下其他类（便于 lead 自由集成）。
 */
public final class NetClient {
    /** 客户端预测状态：位置 m、速度 m/s、朝向 rad。 */
    public static final class PredictedState {
        public final float x, y, z, yaw, speed;
        PredictedState(float x, float y, float z, float yaw, float speed) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.speed = speed;
        }
        @Override public String toString() {
            return "PredictedState{x=" + x + ", y=" + y + ", z=" + z
                    + ", yaw=" + yaw + ", speed=" + speed + "}";
        }
    }

    /** 可调配置；构造后 connect 前修改生效。 */
    public static final class NetConfig {
        public int room = 0;                 // 默认房间
        public int trackIndex = 0;           // 默认赛道
        public int socketTimeoutMs = 200;    // 接收超时（控制重连检测粒度）
        public int maxSnapshotQueue = 120;   // 快照队列上限（2 秒 @60Hz，防内存膨胀）
        public long reconnectIntervalMs = 1500L; // 断线后重连尝试间隔
        public boolean autoReconnect = true; // 是否自动重连
        public float tickSeconds = 1f / 60f; // 预测积分步长，须与服务端 60Hz 一致
    }

    /** 输入历史项，用于和解重放。 */
    private static final class InputItem {
        final int seq;
        final float throttle, brake, steer;
        InputItem(int seq, float throttle, float brake, float steer) {
            this.seq = seq;
            this.throttle = throttle;
            this.brake = brake;
            this.steer = steer;
        }
    }

    private static final float MAX_SPEED = 68f;
    private static final float ACCEL = 22f;
    private static final float BRAKE = 38f;
    private static final float DRAG = 0.16f;
    private static final float STEER_SMOOTH = 9f;
    private static final float TURN_FACTOR = 1.85f;
    private static final float MAX_HISTORY = 240; // 4 秒 @60Hz

    private final NetConfig config = new NetConfig();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean stop = new AtomicBoolean(true);
    private final AtomicInteger clientId = new AtomicInteger(0);
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicLong lastServerTimeMs = new AtomicLong(0);
    private final AtomicInteger lastAckSeq = new AtomicInteger(-1);
    private final AtomicInteger droppedSnapshots = new AtomicInteger(0);
    private volatile String lastError = "";
    private volatile String host = "";
    private volatile int port = 7777;

    private final Object ioLock = new Object();
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private Thread recvThread;
    private Thread heartbeatThread;

    // 快照队列：接收线程生产，调用方 getSnapshots 消费。
    private final Object queueLock = new Object();
    private final ArrayDeque<Protocol.Snapshot> snapshotQueue = new ArrayDeque<Protocol.Snapshot>();
    private long receiveIndex = 0;

    // 预测状态与输入历史由接收线程（和解）和发送方（预测）访问，用锁保护。
    private final Object predictLock = new Object();
    private float px, py, pz, pyaw, pspeed, pSteerSmooth;
    private final ArrayDeque<InputItem> history = new ArrayDeque<InputItem>();
    private volatile PredictedState predicted = new PredictedState(0f, 0f, 0f, 0f, 0f);

    // 其他车的插值缓冲：clientId -> 上一帧/当前帧权威状态与时间戳。
    private static final class InterpEntry {
        Protocol.CarState prev, target;
        long prevMs, targetMs;
    }
    private final Map<Integer, InterpEntry> interp = new ConcurrentHashMap<Integer, InterpEntry>();

    public NetConfig config() { return config; }
    public boolean isConnected() { return connected.get(); }
    public int clientId() { return clientId.get(); }
    public String lastError() { return lastError; }
    public int droppedSnapshots() { return droppedSnapshots.get(); }

    /**
     * 连接服务端并完成 HELLO + JOIN 握手。同步方法，失败返回 false 并设置 lastError。
     * 成功后启动接收线程和输入心跳线程。
     */
    public boolean connect(String host, int port) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            lastError = "invalid host or port";
            return false;
        }
        // 只关闭旧 socket 与接收线程，保留 heartbeat 线程以便自动重连循环存活。
        stop.set(true);
        connected.set(false);
        stopRecvOnly();
        stop.set(false);
        this.host = host;
        this.port = port;
        DatagramSocket sock = null;
        try {
            InetAddress addr = InetAddress.getByName(host);
            sock = new DatagramSocket();
            sock.setSoTimeout(config.socketTimeoutMs);
            sock.connect(addr, port);
            serverAddress = addr;

            // HELLO 握手：超时重试 3 次，防首包丢失。
            byte[] helloAck = null;
            for (int attempt = 0; attempt < 3 && !stop.get(); attempt++) {
                sock.send(new DatagramPacket(Protocol.buildHello(), Protocol.HELLO_SIZE, addr, port));
                helloAck = receiveOfType(sock, Protocol.HELLO_ACK, 1000);
                if (helloAck != null) break;
            }
            if (helloAck == null) {
                lastError = "HELLO_ACK timeout";
                sock.close();
                return false;
            }
            int cid = Protocol.parseHelloAck(helloAck);
            clientId.set(cid);

            // JOIN 握手：重试直到 JOIN_ACK（自动重连由上层 heartbeat 处理）。
            byte[] joinAck = null;
            for (int attempt = 0; attempt < 3 && !stop.get(); attempt++) {
                sock.send(new DatagramPacket(
                        Protocol.buildJoin(cid, config.room, config.trackIndex),
                        Protocol.JOIN_SIZE, addr, port));
                joinAck = receiveOfType(sock, Protocol.JOIN_ACK, 1000);
                if (joinAck != null) break;
            }
            if (joinAck == null) {
                lastError = "JOIN_ACK timeout";
                sock.close();
                return false;
            }
            Protocol.JoinResult jr = Protocol.parseJoinAck(joinAck);
            if (!jr.accepted()) {
                lastError = "JOIN rejected, slot=" + jr.slot;
                sock.close();
                return false;
            }

            socket = sock;
            synchronized (queueLock) { snapshotQueue.clear(); receiveIndex = 0; }
            synchronized (predictLock) {
                px = py = pz = pyaw = pspeed = pSteerSmooth = 0f;
                history.clear();
                lastAckSeq.set(-1);
            }
            interp.clear();
            connected.set(true);
            lastError = "";
            startThreads();
            return true;
        } catch (Exception e) {
            lastError = "connect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            if (sock != null) sock.close();
            connected.set(false);
            return false;
        }
    }

    /** 接收指定类型的包，直到超时。忽略其他类型（如握手期间到达的快照）。 */
    private byte[] receiveOfType(DatagramSocket sock, byte want, int timeoutMs) throws Exception {
        int old = sock.getSoTimeout();
        sock.setSoTimeout(timeoutMs);
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[Protocol.CAR_RECORD * Protocol.MAX_CARS + Protocol.SNAPSHOT_HEADER + 64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (p.getLength() > 0 && p.getData()[0] == want) {
                    byte[] out = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, out, 0, p.getLength());
                    return out;
                }
            }
        } finally {
            sock.setSoTimeout(old);
        }
        return null;
    }

    private void startThreads() {
        // 接收线程每次连接新建；心跳线程全局只有一个，避免自动重连时重复启动。
        recvThread = new Thread(new Runnable() {
            @Override public void run() { receiveLoop(); }
        }, "net-recv");
        recvThread.setDaemon(true);
        recvThread.start();

        if (heartbeatThread == null || !heartbeatThread.isAlive()) {
            heartbeatThread = new Thread(new Runnable() {
                @Override public void run() { heartbeatLoop(); }
            }, "net-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }
    }

    /** 停止接收线程并关闭 socket，不影响 heartbeat 线程。 */
    private void stopRecvOnly() {
        DatagramSocket sock;
        synchronized (ioLock) {
            sock = socket;
            socket = null;
        }
        if (sock != null) sock.close();
        Thread r = recvThread;
        recvThread = null;
        if (r != null) r.interrupt();
    }

    /**
     * 发送一帧输入。seq 自增；同时把输入写入历史并做客户端预测积分。
     * 线程安全：可从任意线程调用（通常渲染/逻辑线程）。
     */
    public void sendInput(float throttle, float brake, float steer) {
        if (!connected.get()) return;
        float t = clamp(throttle, 0f, 1f);
        float bk = clamp(brake, 0f, 1f);
        float s = clamp(steer, -1f, 1f);
        int mySeq = seq.incrementAndGet();
        byte[] packet = Protocol.buildInput(clientId.get(), mySeq, t, bk, s);
        DatagramSocket sock = socket;
        if (sock == null || !connected.get()) return;
        try {
            synchronized (ioLock) {
                if (connected.get()) {
                    sock.send(new DatagramPacket(packet, packet.length, serverAddress, port));
                }
            }
        } catch (Exception e) {
            lastError = "sendInput failed: " + e.getMessage();
            connected.set(false);
            return;
        }
        // 客户端预测：把输入作用到本地状态，立即推进一帧。
        synchronized (predictLock) {
            history.addLast(new InputItem(mySeq, t, bk, s));
            while (history.size() > MAX_HISTORY) history.removeFirst();
            predictStep(t, bk, s, config.tickSeconds);
            publishPredicted();
        }
    }

    /** 客户端预测积分：与服务端权威物理同参数。 */
    private void predictStep(float throttle, float brake, float steer, float dt) {
        pSteerSmooth += (steer - pSteerSmooth) * Math.min(1f, dt * STEER_SMOOTH);
        float accel = throttle * ACCEL - brake * BRAKE - pspeed * DRAG;
        pspeed = clamp(pspeed + accel * dt, 0f, MAX_SPEED);
        float turnRate = (pspeed / 24f) * pSteerSmooth * TURN_FACTOR;
        pyaw = wrapAngle(pyaw + turnRate * dt);
        float slip = pspeed * pSteerSmooth * 0.09f;
        float fx = (float) Math.sin(pyaw), fz = (float) Math.cos(pyaw);
        float rx = (float) Math.cos(pyaw), rz = -(float) Math.sin(pyaw);
        px += (fx * pspeed + rx * slip) * dt;
        pz += (fz * pspeed + rz * slip) * dt;
    }

    private void publishPredicted() {
        predicted = new PredictedState(px, py, pz, pyaw, pspeed);
    }

    public PredictedState getPredictedState() {
        return predicted;
    }

    /** 接收线程：收包 -> 解析快照 -> 和解 -> 入队 -> 更新插值缓冲。 */
    private void receiveLoop() {
        while (!stop.get() && connected.get()) {
            DatagramSocket sock = socket;
            if (sock == null) return;
            try {
                byte[] buf = new byte[Protocol.CAR_RECORD * Protocol.MAX_CARS + Protocol.SNAPSHOT_HEADER + 64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                if (p.getLength() <= 0) continue;
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), 0, data, 0, p.getLength());
                if (data[0] != Protocol.SNAPSHOT) continue;
                long idx;
                synchronized (queueLock) { idx = ++receiveIndex; }
                Protocol.Snapshot snap = Protocol.parseSnapshot(data, idx);
                if (snap == null) {
                    droppedSnapshots.incrementAndGet();
                    continue;
                }
                lastServerTimeMs.set(snap.timeMs);
                reconcile(snap);
                updateInterpolation(snap);
                synchronized (queueLock) {
                    if (snapshotQueue.size() >= config.maxSnapshotQueue) snapshotQueue.removeFirst();
                    snapshotQueue.addLast(snap);
                }
            } catch (SocketTimeoutException e) {
                // 正常超时，继续循环检查连接标志。
            } catch (Exception e) {
                if (!stop.get()) lastError = "receive failed: " + e.getMessage();
                connected.set(false);
                break;
            }
        }
    }

    /**
     * 服务端和解：把本地预测状态拉回服务端权威状态，再重放所有未被确认的输入。
     * 只重放 seq > ackSeq 的输入，避免重复应用已确认输入。
     */
    private void reconcile(Protocol.Snapshot snap) {
        Protocol.CarState me = snap.you();
        if (me == null) return;
        synchronized (predictLock) {
            int ack = snap.ackSeq;
            lastAckSeq.set(ack);
            // 丢弃已被服务端确认的输入。
            while (!history.isEmpty() && history.peekFirst().seq <= ack) history.removeFirst();
            // 以服务端权威状态为基准。
            px = me.x; py = me.y; pz = me.z; pyaw = me.yaw; pspeed = me.speed;
            // 用最后一次已确认输入近似恢复转向平滑量。
            pSteerSmooth = clamp(me.steer, -1f, 1f);
            // 重放未确认输入。
            for (InputItem item : history) {
                predictStep(item.throttle, item.brake, item.steer, config.tickSeconds);
            }
            publishPredicted();
        }
    }

    /** 更新其他车的插值缓冲：把上一目标作为新起点，服务端状态作为新目标。 */
    private void updateInterpolation(Protocol.Snapshot snap) {
        for (int i = 0; i < snap.cars.size(); i++) {
            Protocol.CarState c = snap.cars.get(i);
            if (c.clientId == snap.yourClientId) continue;
            InterpEntry e = interp.get(c.clientId);
            if (e == null) {
                e = new InterpEntry();
                e.prev = c;
                e.target = c;
                e.prevMs = snap.timeMs;
                e.targetMs = snap.timeMs;
                interp.put(c.clientId, e);
            } else {
                e.prev = e.target;
                e.prevMs = e.targetMs;
                e.target = c;
                e.targetMs = snap.timeMs;
            }
        }
    }

    /**
     * 取其他车的插值显示状态（供渲染线程按当前时间调用）。
     * 线性插值位置与朝向，速度取目标值。无数据返回 null。
     */
    public Protocol.CarState getInterpolated(int clientId) {
        InterpEntry e = interp.get(clientId);
        if (e == null || e.target == null) return null;
        long span = e.targetMs - e.prevMs;
        float a = span > 0
                ? clamp((float) (System.currentTimeMillis() - e.targetMs) / (float) span + 1f, 0f, 1f)
                : 1f;
        Protocol.CarState p = e.prev;
        Protocol.CarState t = e.target;
        float x = p.x + (t.x - p.x) * a;
        float y = p.y + (t.y - p.y) * a;
        float z = p.z + (t.z - p.z) * a;
        float yaw = p.yaw + wrapAngle(t.yaw - p.yaw) * a;
        return new Protocol.CarState(t.clientId, t.slot, x, y, z, yaw,
                t.speed, t.place, t.trackIndex, t.lap, t.finished,
                t.throttle, t.brake, t.steer);
    }

    /** 取其他车最近一次权威状态（不需要插值时用）。 */
    public Protocol.CarState getRemoteState(int clientId) {
        InterpEntry e = interp.get(clientId);
        return e == null ? null : e.target;
    }

    /**
     * 取出并清空自上次调用以来收到的快照。返回不可变列表快照，线程安全。
     */
    public List<Protocol.Snapshot> getSnapshots() {
        synchronized (queueLock) {
            if (snapshotQueue.isEmpty()) return Collections.emptyList();
            List<Protocol.Snapshot> out = new ArrayList<Protocol.Snapshot>(snapshotQueue);
            snapshotQueue.clear();
            return Collections.unmodifiableList(out);
        }
    }

    /** 心跳线程：检测断线并按配置自动重连。 */
    private void heartbeatLoop() {
        long lastTry = 0;
        while (!stop.get()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (connected.get()) continue;
            if (!config.autoReconnect || stop.get()) continue;
            long now = System.currentTimeMillis();
            if (now - lastTry < config.reconnectIntervalMs) continue;
            lastTry = now;
            if (connect(host, port)) {
                lastError = "";
            }
        }
    }

    /** 断开连接，停止所有线程，清空状态。可重复调用。 */
    public void disconnect() {
        stop.set(true);
        connected.set(false);
        DatagramSocket sock;
        synchronized (ioLock) {
            sock = socket;
            socket = null;
        }
        if (sock != null) sock.close();
        Thread r = recvThread, h = heartbeatThread;
        recvThread = null;
        heartbeatThread = null;
        if (r != null) r.interrupt();
        if (h != null) h.interrupt();
        synchronized (queueLock) { snapshotQueue.clear(); receiveIndex = 0; }
        synchronized (predictLock) { history.clear(); }
        interp.clear();
        clientId.set(0);
        seq.set(0);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float wrapAngle(float a) {
        while (a > 3.14159265f) a -= 6.28318531f;
        while (a < -3.14159265f) a += 6.28318531f;
        return a;
    }
}
