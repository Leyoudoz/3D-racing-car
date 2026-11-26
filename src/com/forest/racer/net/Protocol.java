package com.forest.racer.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络协议定义与编解码（小端紧凑二进制），与服务端 server/protocol.h 一一对应。
 * 纯 JDK，无第三方库。单位：坐标 m，速度 m/s，角度 rad，时间 ms。
 */
public final class Protocol {
    private Protocol() {}

    public static final int VERSION = 1;
    public static final int MAX_CARS = 6;

    // 消息类型（与服务端一致）。
    public static final byte HELLO = 0x01;
    public static final byte JOIN = 0x02;
    public static final byte INPUT = 0x03;
    public static final byte HELLO_ACK = (byte) 0x81;
    public static final byte SNAPSHOT = (byte) 0x82;
    public static final byte JOIN_ACK = (byte) 0x83;

    // 固定长度（字节）。
    public static final int HELLO_SIZE = 5;
    public static final int JOIN_SIZE = 9;
    public static final int INPUT_SIZE = 12;
    public static final int HELLO_ACK_SIZE = 9;
    public static final int JOIN_ACK_SIZE = 8;
    public static final int SNAPSHOT_HEADER = 22;
    public static final int CAR_RECORD = 34;

    /** 单辆车的权威快照状态。 */
    public static final class CarState {
        public final int clientId;
        public final int slot;
        public final float x;
        public final float y;
        public final float z;
        public final float yaw;
        public final float speed;
        public final int place;
        public final int trackIndex;
        public final int lap;
        public final boolean finished;
        public final float throttle;
        public final float brake;
        public final float steer;

        CarState(int clientId, int slot, float x, float y, float z, float yaw,
                 float speed, int place, int trackIndex, int lap, boolean finished,
                 float throttle, float brake, float steer) {
            this.clientId = clientId;
            this.slot = slot;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.speed = speed;
            this.place = place;
            this.trackIndex = trackIndex;
            this.lap = lap;
            this.finished = finished;
            this.throttle = throttle;
            this.brake = brake;
            this.steer = steer;
        }

        @Override
        public String toString() {
            return "CarState{slot=" + slot + ", x=" + x + ", y=" + y + ", z=" + z
                    + ", yaw=" + yaw + ", speed=" + speed + ", place=" + place
                    + ", lap=" + lap + ", finished=" + finished + "}";
        }
    }

    /** 一帧服务端快照。 */
    public static final class Snapshot {
        public final long tick;
        public final long timeMs;
        public final int yourClientId;
        public final int yourSlot;
        public final int room;
        public final int trackIndex;
        /** 服务端已处理的输入序号，用于客户端和解。 */
        public final int ackSeq;
        /** 所有车的权威状态（含自己）。 */
        public final List<CarState> cars;
        /** 接收端单调序号，便于检测丢帧。 */
        public final long receiveIndex;

        Snapshot(long tick, long timeMs, int yourClientId, int yourSlot,
                 int room, int trackIndex, int ackSeq, List<CarState> cars, long receiveIndex) {
            this.tick = tick;
            this.timeMs = timeMs;
            this.yourClientId = yourClientId;
            this.yourSlot = yourSlot;
            this.room = room;
            this.trackIndex = trackIndex;
            this.ackSeq = ackSeq;
            this.cars = cars;
            this.receiveIndex = receiveIndex;
        }

        /** 取自己的权威车状态；不存在返回 null。 */
        public CarState you() {
            for (int i = 0; i < cars.size(); i++) {
                if (cars.get(i).clientId == yourClientId) return cars.get(i);
            }
            return null;
        }
    }

    /** 构造 HELLO 包。 */
    public static byte[] buildHello() {
        ByteBuffer b = ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(HELLO).putInt(VERSION);
        return b.array();
    }

    /** 构造 JOIN 包。 */
    public static byte[] buildJoin(int clientId, int room, int trackIndex) {
        ByteBuffer b = ByteBuffer.allocate(JOIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(JOIN).putInt(clientId).putShort((short) room)
         .put((byte) trackIndex).put((byte) 0);
        return b.array();
    }

    /**
     * 构造 INPUT 包。输入先钳制：throttle/brake 限 [0,1]，steer 限 [-1,1]，
     * 量化到 u8/i8。客户端也做一次钳制，防异常输入导致服务端拒绝。
     */
    public static byte[] buildInput(int clientId, int seq, float throttle, float brake, float steer) {
        float t = clamp(throttle, 0f, 1f);
        float bk = clamp(brake, 0f, 1f);
        float s = clamp(steer, -1f, 1f);
        ByteBuffer b = ByteBuffer.allocate(INPUT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(INPUT).putInt(clientId).putInt(seq)
         .put((byte) Math.round(t * 255f))
         .put((byte) Math.round(bk * 255f))
         .put((byte) Math.round(s * 127f));
        return b.array();
    }

    /** 解析 HELLO_ACK，返回 clientId；长度不足或类型错抛 IllegalArgumentException。 */
    public static int parseHelloAck(byte[] data) {
        if (data == null || data.length < HELLO_ACK_SIZE || data[0] != HELLO_ACK) {
            throw new IllegalArgumentException("invalid HELLO_ACK");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.get();
        return b.getInt();
    }

    /** JOIN_ACK 结果：slot 为 255 表示被拒绝。 */
    public static final class JoinResult {
        public final int clientId;
        public final int slot;
        public final int room;
        public JoinResult(int clientId, int slot, int room) {
            this.clientId = clientId;
            this.slot = slot;
            this.room = room;
        }
        public boolean accepted() { return slot >= 0 && slot < MAX_CARS; }
    }

    public static JoinResult parseJoinAck(byte[] data) {
        if (data == null || data.length < JOIN_ACK_SIZE || data[0] != JOIN_ACK) {
            throw new IllegalArgumentException("invalid JOIN_ACK");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.get();
        int clientId = b.getInt();
        int slot = b.get() & 0xFF;
        int room = b.getShort() & 0xFFFF;
        return new JoinResult(clientId, slot, room);
    }

    /** 解析 SNAPSHOT；数据不完整或类型不符返回 null（调用方忽略坏包）。 */
    public static Snapshot parseSnapshot(byte[] data, long receiveIndex) {
        if (data == null || data.length < SNAPSHOT_HEADER || data[0] != SNAPSHOT) return null;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.get();
        long tick = b.getInt() & 0xFFFFFFFFL;
        long timeMs = b.getInt() & 0xFFFFFFFFL;
        int yourClientId = b.getInt();
        int yourSlot = b.get() & 0xFF;
        int numCars = b.get() & 0xFF;
        int room = b.getShort() & 0xFFFF;
        int trackIndex = b.get() & 0xFF;
        int ackSeq = b.getInt();
        if (numCars < 0 || numCars > MAX_CARS) return null;
        if (data.length < SNAPSHOT_HEADER + numCars * CAR_RECORD) return null;
        List<CarState> cars = new ArrayList<CarState>(numCars);
        for (int i = 0; i < numCars; i++) {
            int clientId = b.getInt();
            int slot = b.get() & 0xFF;
            float x = b.getFloat();
            float y = b.getFloat();
            float z = b.getFloat();
            float yaw = b.getFloat();
            float speed = b.getFloat();
            int place = b.get() & 0xFF;
            int carTrack = b.get() & 0xFF;
            int lap = b.get() & 0xFF;
            boolean finished = (b.get() & 0xFF) != 0;
            b.get(); // reserved
            float throttle = (b.get() & 0xFF) / 255f;
            float brake = (b.get() & 0xFF) / 255f;
            float steer = (b.get() & 0xFF) / 127f;
            b.get(); // reserved
            cars.add(new CarState(clientId, slot, x, y, z, yaw, speed,
                    place, carTrack, lap, finished, throttle, brake, steer));
        }
        return new Snapshot(tick, timeMs, yourClientId, yourSlot,
                room, trackIndex, ackSeq, cars, receiveIndex);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
