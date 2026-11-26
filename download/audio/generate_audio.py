#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
森林赛车音效合成脚本（仅依赖 numpy）。
生成 WAV 素材，并通过 ffmpeg 转 OGG/Vorbis。
所有素材为程序化合成，无第三方版权素材。
"""
import os
import math
import struct
import subprocess
import numpy as np

SR = 44100
OUT = os.path.dirname(os.path.abspath(__file__))
rng = np.random.default_rng(20261126)


def norm16(x):
    x = np.asarray(x, dtype=np.float64)
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    if peak > 1e-9:
        x = x / peak * 0.92
    else:
        x = np.zeros_like(x)
    return np.clip(x, -1.0, 1.0)


def write_wav(path, data):
    d = (norm16(data) * 32767.0).astype(np.int16)
    with wave_open(path) as w:
        w.writeframes(d.tobytes())
    print("WAV %s frames=%d bytes=%d" % (os.path.basename(path), d.size, os.path.getsize(path)))


class wave_open(object):
    def __init__(self, path):
        self.path = path

    def __enter__(self):
        self.f = open(self.path, "wb")
        self.f.write(b"RIFF")
        self.f.write(b"\x00\x00\x00\x00")
        self.f.write(b"WAVEfmt ")
        self.f.write(struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16))
        self.f.write(b"data")
        self.f.write(b"\x00\x00\x00\x00")
        self._data_at = self.f.tell()
        return self

    def writeframes(self, raw):
        self.f.write(raw)

    def __exit__(self, *args):
        total = self.f.tell()
        data_bytes = total - self._data_at
        self.f.seek(4)
        self.f.write(struct.pack("<I", total - 8))
        self.f.seek(self._data_at - 4)
        self.f.write(struct.pack("<I", data_bytes))
        self.f.close()


def t_axis(dur):
    return np.arange(int(dur * SR), dtype=np.float64) / SR


def loop_crossfade(x, fade_s=0.05):
    n = int(fade_s * SR)
    if n < 2 or x.size < n * 2:
        return x
    head = x[:n].copy()
    tail = x[-n:].copy()
    w = np.linspace(0.0, 1.0, n)
    # 把尾部渐变融入头部，使首尾连续
    x[:n] = head * w + tail * (1.0 - w)
    return x


def engine_layer(dur, f0, harmonics, bright, seed_jitter):
    t = t_axis(dur)
    # 让基频恰好整周期，保证循环无缝
    f0 = round(f0 * dur) / dur
    sig = np.zeros_like(t)
    for k in range(1, harmonics + 1):
        amp = 1.0 / (k ** bright)
        phase = rng.uniform(0.0, 2.0 * math.pi)
        sig += amp * np.sin(2.0 * math.pi * (f0 * k) * t + phase)
    # 轻微抖动模拟机械不稳，频率同样取整周期
    lfo = 4.0 / dur
    sig *= 1.0 + 0.06 * np.sin(2.0 * math.pi * lfo * t)
    if seed_jitter:
        noise = rng.normal(0.0, 0.03, t.size)
        sig += noise
    return loop_crossfade(sig, 0.06)


def filtered_noise(dur, cutoff, kind="low", seed=0):
    r = np.random.default_rng(seed)
    t = t_axis(dur)
    white = r.normal(0.0, 1.0, t.size)
    # 频域一阶低通/高通，避免引入 scipy 依赖
    spec = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(t.size, 1.0 / SR)
    if kind == "low":
        resp = 1.0 / (1.0 + (freqs / cutoff) ** 2)
    elif kind == "high":
        resp = (freqs / cutoff) ** 2 / (1.0 + (freqs / cutoff) ** 2)
    else:
        resp = np.ones_like(freqs)
    spec *= resp
    out = np.fft.irfft(spec, n=t.size)
    return loop_crossfade(out, 0.08)


def adsr(n, attack, decay, sustain, release):
    a = max(1, int(attack * SR))
    d = max(1, int(decay * SR))
    r = max(1, int(release * SR))
    s = max(1, n - a - d - r)
    env = np.concatenate([
        np.linspace(0.0, 1.0, a),
        np.linspace(1.0, sustain, d),
        np.full(s, sustain),
        np.linspace(sustain, 0.0, r),
    ])
    if env.size < n:
        env = np.pad(env, (0, n - env.size), "constant")
    return env[:n]


def tone(freq, dur, harmonics=4, bright=2.0):
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for k in range(1, harmonics + 1):
        sig += (1.0 / (k ** bright)) * np.sin(2.0 * math.pi * freq * k * t)
    return sig


def one_shot(path, sig):
    n = sig.size
    env = adsr(n, 0.005, 0.02, 0.9, min(0.15, n / SR * 0.4))
    write_wav(path, sig * env)


def encode_ogg(wav_path, ogg_path, quality=4):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
         "-c:a", "libvorbis", "-q:a", str(quality), ogg_path],
        check=True,
    )
    print("OGG %s bytes=%d" % (os.path.basename(ogg_path), os.path.getsize(ogg_path)))


def gen_engine():
    write_wav(os.path.join(OUT, "engine_low.wav"), engine_layer(2.0, 58.0, 10, 1.7, True))
    write_wav(os.path.join(OUT, "engine_high.wav"), engine_layer(2.0, 132.0, 14, 1.2, True))


def gen_skid():
    dur = 1.6
    t = t_axis(dur)
    noise = filtered_noise(dur, 2600.0, "high", seed=11) * 0.6
    screech = np.sin(2.0 * math.pi * 1730.0 * t) * 0.25
    screech += np.sin(2.0 * math.pi * 2380.0 * t) * 0.15
    write_wav(os.path.join(OUT, "tire_skid.wav"), loop_crossfade(noise + screech, 0.1))


def gen_wind():
    dur = 2.0
    wind = filtered_noise(dur, 420.0, "low", seed=22)
    gust = 0.5 + 0.5 * np.sin(2.0 * math.pi * (1.0 / dur) * t_axis(dur))
    write_wav(os.path.join(OUT, "wind.wav"), loop_crossfade(wind * gust, 0.12))


def gen_brake():
    dur = 0.7
    t = t_axis(dur)
    f = 2100.0 - 1500.0 * (t / dur)
    phase = 2.0 * math.pi * np.cumsum(f) / SR
    sig = np.sin(phase) * 0.4 + filtered_noise(dur, 3000.0, "high", 33) * 0.3
    one_shot(os.path.join(OUT, "brake.wav"), sig)


def gen_gear():
    dur = 0.35
    t = t_axis(dur)
    click = np.exp(-t * 60.0) * (rng.normal(0.0, 1.0, t.size) * 0.5)
    whoosh = filtered_noise(dur, 1500.0, "low", 34)
    whoosh *= np.exp(-((t - 0.12) ** 2) / (2 * 0.05 ** 2))
    one_shot(os.path.join(OUT, "gear_shift.wav"), click + whoosh * 0.5)


def gen_countdown():
    one_shot(os.path.join(OUT, "countdown.wav"), tone(880.0, 0.18, harmonics=3))


def gen_win():
    dur = 1.6
    notes = [523.25, 659.25, 783.99, 1046.5]
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for i, f in enumerate(notes):
        start = int(i * 0.22 * SR)
        seg = tone(f, 0.7, harmonics=5, bright=1.5)
        end = min(sig.size, start + seg.size)
        sig[start:end] += seg[: end - start]
    chord = tone(261.63, dur, 3) * 0.15
    write_wav(os.path.join(OUT, "win.wav"), adsr(sig.size, 0.01, 0.05, 0.85, 0.3) * (sig + chord))


def gen_lose():
    dur = 1.5
    t = t_axis(dur)
    f = np.linspace(440.0, 180.0, t.size)
    sig = np.sin(2.0 * math.pi * np.cumsum(f) / SR)
    sig += 0.3 * np.sin(2.0 * math.pi * np.cumsum(f * 0.5) / SR)
    write_wav(os.path.join(OUT, "lose.wav"), adsr(sig.size, 0.02, 0.05, 0.8, 0.4) * sig)


def gen_birds():
    dur = 10.0
    t = t_axis(dur)
    bed = filtered_noise(dur, 900.0, "low", 44) * 0.08
    sig = bed.copy()
    chirps = 26
    for _ in range(chirps):
        start = rng.uniform(0.0, dur - 0.4)
        f0 = rng.uniform(1800.0, 3600.0)
        length = rng.uniform(0.06, 0.16)
        n = int(length * SR)
        ct = np.arange(n) / SR
        sweep = f0 * (1.0 + 0.5 * ct / length)
        chirp = np.sin(2.0 * math.pi * np.cumsum(sweep) / SR)
        chirp *= np.sin(math.pi * np.arange(n) / n) ** 2
        s = int(start * SR)
        e = min(sig.size, s + n)
        if e > s:
            sig[s:e] += chirp[: e - s] * 0.25
    wav_path = os.path.join(OUT, "_ambient_birds.wav")
    write_wav(wav_path, loop_crossfade(sig, 0.15))
    encode_ogg(wav_path, os.path.join(OUT, "ambient_birds.ogg"), 4)
    os.remove(wav_path)


def render_music(name, seed, root, scale, bars, bpm):
    r = np.random.default_rng(seed)
    beat = 60.0 / bpm
    dur = bars * 4 * beat + 1.0
    t = t_axis(dur)
    sig = np.zeros_like(t)

    def add_note(start, freq, length, amp, harmonics=3):
        n = int(length * SR)
        if n <= 0:
            return
        nt = np.arange(n) / SR
        wave = np.zeros(n)
        for k in range(1, harmonics + 1):
            wave += (1.0 / (k ** 1.6)) * np.sin(2.0 * math.pi * freq * k * nt)
        env = adsr(n, 0.02, 0.08, 0.7, min(0.25, length * 0.4))
        s = int(start * SR)
        e = min(sig.size, s + n)
        if e > s:
            sig[s:e] += wave[: e - s] * env[: e - s] * amp

    # 和声铺底
    progression = [0, -3, 5, 3]
    for bar in range(bars):
        chord_root = root * (2 ** (progression[bar % len(progression)] / 12.0))
        for mult in (1.0, 1.5, 2.0):
            add_note(bar * 4 * beat, chord_root * mult, 4 * beat, 0.12, 2)
    # 旋律
    step = beat / 2.0
    count = int(bars * 4 * beat / step)
    idx = 0
    for _ in range(count):
        if r.random() < 0.78:
            semi = scale[int(r.integers(0, len(scale)))]
            freq = root * 2 * (2 ** (semi / 12.0))
            add_note(idx * step, freq, step * r.uniform(0.8, 1.6), 0.18, 3)
        idx += 1
    # 轻打击点
    for b in range(bars * 4):
        add_note(b * beat, 90.0, 0.12, 0.1, 1)

    wav_path = os.path.join(OUT, "_%s.wav" % name)
    write_wav(wav_path, sig)
    encode_ogg(wav_path, os.path.join(OUT, name + ".ogg"), 4)
    os.remove(wav_path)


def main():
    print("audio generator start, SR=%d, numpy=%s" % (SR, np.__version__))
    gen_engine()
    gen_skid()
    gen_wind()
    gen_brake()
    gen_gear()
    gen_countdown()
    gen_win()
    gen_lose()
    gen_birds()
    render_music("music2", 101, 261.63, [0, 2, 4, 7, 9, 12], 8, 96)
    render_music("music3", 202, 220.00, [0, 3, 5, 7, 10, 12], 8, 84)
    print("audio generator done")


if __name__ == "__main__":
    main()
