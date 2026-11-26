#!/usr/bin/env bash
# 服务端端到端网络测试：启动服务端 -> HELLO/JOIN/INPUT -> 校验 SNAPSHOT 字段 -> 关闭。
# 用法: ./tests/test_net.sh [port]   （默认 7788，避免与默认 7777 冲突）
# 通过标准: 收到 HELLO_ACK、JOIN_ACK(slot!=255)、至少 3 帧 SNAPSHOT，
#           且 SNAPSHOT 中 clientId/room/track/slot 匹配、至少一辆车 speed 受上限约束。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${1:-7788}"
SERVER_BIN="$ROOT/server/forest_racer_server"
TRACE_PY="$ROOT/tests/.net_client_test.py"

if [ ! -x "$SERVER_BIN" ]; then
  echo "ERROR: server binary not found: $SERVER_BIN" >&2
  echo "Run: $ROOT/server/build_server.sh" >&2
  exit 1
fi

cleanup() {
  if [ -n "${SRV_PID:-}" ] && kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null || true
    wait "$SRV_PID" 2>/dev/null || true
  fi
  rm -f "$TRACE_PY" "$ROOT/tests/.net_server.log" "$ROOT/tests/.net_server_out.log"
}
trap cleanup EXIT INT TERM

# 启动服务端（后台，日志写文件便于核对）。
"$SERVER_BIN" --port "$PORT" --min-level INFO >"$ROOT/tests/.net_server_out.log" 2>"$ROOT/tests/.net_server.log" &
SRV_PID=$!
sleep 0.5
if ! kill -0 "$SRV_PID" 2>/dev/null; then
  echo "ERROR: server exited immediately" >&2
  cat "$ROOT/tests/.net_server.log" >&2
  exit 1
fi

cat > "$TRACE_PY" <<'PYEOF'
import socket, struct, sys, time

PORT = int(sys.argv[1])
VER = 1
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(0.2)
s.bind(('127.0.0.1', 0))
addr = ('127.0.0.1', PORT)

def recv_until(want_type, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            d, _ = s.recvfrom(4096)
        except socket.timeout:
            continue
        if len(d) == 0:
            continue
        if d[0] == want_type:
            return d
    raise RuntimeError('timeout waiting type=0x%02x' % want_type)

# 1. HELLO -> HELLO_ACK
s.sendto(struct.pack('<BI', 0x01, VER), addr)
ack = recv_until(0x81, 2.0)
if len(ack) < 9:
    raise RuntimeError('short HELLO_ACK')
_, client_id, srv_ms = struct.unpack('<BII', ack[:9])
print('HELLO_ACK client_id=0x%08x srv_ms=%d' % (client_id, srv_ms))

# 2. JOIN room=2 track=5 -> JOIN_ACK
join = struct.pack('<BIHBB', 0x02, client_id, 2, 5, 0)
s.sendto(join, addr)
jack = recv_until(0x83, 2.0)
if len(jack) < 8:
    raise RuntimeError('short JOIN_ACK')
_, cid2, slot, room = struct.unpack('<BIBH', jack[:8])
if slot == 255:
    raise RuntimeError('JOIN rejected slot=255')
print('JOIN_ACK client_id=0x%08x slot=%d room=%d' % (cid2, slot, room))

# 3. INPUT：满油门 + 右转，发送 1 秒。
seq = 0
frames = []
end = time.time() + 1.5
throttle = 255
steer = 127
while time.time() < end:
    s.sendto(struct.pack('<BIIBBb', 0x03, client_id, seq, throttle, 0, steer), addr)
    seq += 1
    try:
        d, _ = s.recvfrom(4096)
    except socket.timeout:
        continue
    if len(d) >= 22 and d[0] == 0x82:
        frames.append(d)
    if len(frames) >= 3:
        break

if len(frames) < 3:
    raise RuntimeError('expected >=3 snapshots, got %d' % len(frames))

# 4. 解析并校验 SNAPSHOT。
hdr = struct.unpack('<BIIIBBHBI', frames[-1][:22])
typ, tick, tms, your_id, your_slot, ncar, rm, track, ack_seq = hdr
if typ != 0x82 or your_id != client_id or your_slot != slot or rm != 2 or track != 5:
    raise RuntimeError('snapshot header mismatch: %r' % (hdr,))
if ncar < 1 or ncar > 6:
    raise RuntimeError('car count out of range: %d' % ncar)

off = 22
cars = []
for _ in range(ncar):
    if off + 34 > len(frames[-1]):
        raise RuntimeError('short car record')
    rec = struct.unpack('<IBfffffBBBBBBBbB', frames[-1][off:off+34])
    cars.append(rec)
    off += 32

me = [c for c in cars if c[0] == client_id]
if not me:
    raise RuntimeError('own car not present in snapshot')
c = me[0]
x, y, z, yaw, speed = c[2], c[3], c[4], c[5], c[6]
if speed < 0.0 or speed > 68.0 + 1e-3:
    raise RuntimeError('speed out of bounds: %.2f' % speed)
if not (-1000.0 < x < 1000.0 and -1000.0 < z < 1000.0):
    raise RuntimeError('position out of bounds: x=%.2f z=%.2f' % (x, z))

print('SNAPSHOT tick=%d time_ms=%d num_cars=%d room=%d track=%d' % (tick, tms, ncar, rm, track))
print('OWN_CAR x=%.2f y=%.2f z=%.2f yaw=%.2f speed=%.2f place=%d lap=%d' % (
    x, y, z, yaw, speed, c[7], c[9]))
print('NET_TEST_PASSED frames=%d' % len(frames))
PYEOF

python3 "$TRACE_PY" "$PORT"
echo "--- server log tail (first 30 lines) ---"
sed -n '1,30p' "$ROOT/tests/.net_server.log"
