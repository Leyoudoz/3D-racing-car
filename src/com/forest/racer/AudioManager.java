package com.forest.racer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * 音频管理：分层引擎、胎噪、风噪、环境音、碰撞提示、多首循环轻音乐。
 * 素材全部来自 assets/audio/。公开方法保持向后兼容，并新增扩展接口供主循环集成。
 *
 * <p>线程约定：公开方法可在主线程或游戏线程调用，内部通过 synchronized 保护；
 * 音乐切换在调用线程完成prepare，短音效由 SoundPool 异步播放。
 */
final class AudioManager {
    private static final int MAX_STREAMS = 16;
    private static final float MUSIC_VOLUME = 0.55f;
    private static final float AMBIENT_VOLUME = 0.28f;

    /** 音乐列表， music.ogg 为原有素材，music2/music3 为新增素材。 */
    private static final String[] TRACKS = {
            "audio/music.ogg", "audio/music2.ogg", "audio/music3.ogg"
    };

    private final Context ctx;
    private MediaPlayer music;
    private SoundPool pool;

    private int engineLowId;
    private int engineHighId;
    private int beepId;
    private int crashId;
    private int skidId;
    private int brakeId;
    private int gearId;
    private int countdownId;
    private int winId;
    private int loseId;
    private int windId;

    private int engineLowStream;
    private int engineHighStream;
    private int skidStream;
    private int windStream;
    private int ambientStream;

    private boolean musicOn = true;
    private boolean ready;
    private int trackIndex;
    private int lastGear = -1;
    private long lastBrakeMs;

    AudioManager(Context ctx) { this.ctx = ctx; }

    /** 加载全部 SoundPool 素材与首首音乐；失败时保持 ready=false，调用方静默降级。 */
    void init() {
        synchronized (this) {
            if (ready) {
                return;
            }
            try {
                AudioAttributes attr = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                pool = new SoundPool.Builder()
                        .setMaxStreams(MAX_STREAMS)
                        .setAudioAttributes(attr)
                        .build();

                engineLowId = load("audio/engine_low.wav");
                engineHighId = load("audio/engine_high.wav");
                // 向后兼容：若分层素材缺失，回退到旧的单层引擎素材。
                if (engineLowId == 0) {
                    engineLowId = load("audio/engine.wav");
                }
                if (engineHighId == 0) {
                    engineHighId = engineLowId;
                }
                beepId = load("audio/beep.wav");
                crashId = load("audio/crash.wav");
                skidId = load("audio/tire_skid.wav");
                brakeId = load("audio/brake.wav");
                gearId = load("audio/gear_shift.wav");
                countdownId = load("audio/countdown.wav");
                winId = load("audio/win.wav");
                loseId = load("audio/lose.wav");
                windId = load("audio/wind.wav");

                openMusic(trackIndex);
                ready = true;
            } catch (Throwable t) {
                ready = false;
            }
        }
    }

    private int load(String path) {
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(path)) {
            return pool.load(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private void openMusic(int index) {
        if (music != null) {
            music.stop();
            music.release();
            music = null;
        }
        if (index < 0 || index >= TRACKS.length) {
            index = 0;
        }
        trackIndex = index;
        MediaPlayer player = new MediaPlayer();
        AssetFileDescriptor afd = null;
        try {
            afd = ctx.getAssets().openFd(TRACKS[trackIndex]);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            player.setLooping(true);
            player.setVolume(MUSIC_VOLUME, MUSIC_VOLUME);
            player.prepare();
            music = player;
        } catch (Exception e) {
            player.release();
        } finally {
            if (afd != null) {
                try { afd.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 开始播放当前音乐；musicOn=false 时不播放。 */
    void startMusic() {
        synchronized (this) {
            if (ready && musicOn && music != null && !music.isPlaying()) {
                try { music.start(); } catch (Exception ignored) { }
            }
        }
    }

    /** 暂停音乐。 */
    void pauseMusic() {
        synchronized (this) {
            if (music != null && music.isPlaying()) {
                music.pause();
            }
        }
    }

    /** 开关音乐；关闭时暂停，开启时恢复。 */
    void setMusicOn(boolean on) {
        synchronized (this) {
            musicOn = on;
            if (!ready) {
                return;
            }
        }
        if (on) {
            startMusic();
        } else {
            pauseMusic();
        }
    }

    boolean isMusicOn() { synchronized (this) { return musicOn; } }

    /** 启动分层引擎循环声；重复调用无副作用。 */
    void startEngine() {
        synchronized (this) {
            if (!ready || pool == null) {
                return;
            }
            if (engineLowStream == 0 && engineLowId != 0) {
                engineLowStream = pool.play(engineLowId, 0.10f, 0.10f, 1, -1, 1f);
            }
            if (engineHighStream == 0 && engineHighId != 0 && engineHighId != engineLowId) {
                engineHighStream = pool.play(engineHighId, 0.0f, 0.0f, 1, -1, 1f);
            }
        }
    }

    /**
     * 按速度更新引擎、风噪与胎噪。
     *
     * @param speedRatio 速度比，0.0 至 1.0；超出范围会被钳制
     */
    void updateEngine(float speedRatio) {
        updateDrive(speedRatio, 0f, false);
    }

    /**
     * 统一驱动更新入口，供 lead 在游戏循环调用。
     *
     * @param speedRatio 速度比，0.0 至 1.0
     * @param skidRatio  打滑强度，0.0 至 1.0
     * @param braking    是否正在刹车
     */
    void updateDrive(float speedRatio, float skidRatio, boolean braking) {
        synchronized (this) {
            if (!ready || pool == null) {
                return;
            }
            float s = clamp01(speedRatio);
            float skid = clamp01(skidRatio);

            // 引擎分层交叉淡入：低转保持底噪，高转随速度增强。
            float lowVol = 0.13f - 0.06f * s;
            float highVol = 0.02f + 0.16f * s;
            float rateLow = 0.75f + 0.75f * s;
            float rateHigh = 0.85f + 1.15f * s;
            if (engineLowStream != 0) {
                pool.setRate(engineLowStream, rateLow);
                pool.setVolume(engineLowStream, lowVol, lowVol);
            }
            if (engineHighStream != 0) {
                pool.setRate(engineHighStream, rateHigh);
                pool.setVolume(engineHighStream, highVol, highVol);
            }

            // 风噪随速度增大。
            if (windId != 0) {
                if (windStream == 0 && s > 0.05f) {
                    windStream = pool.play(windId, 0.0f, 0.0f, 1, -1, 1f);
                }
                if (windStream != 0) {
                    float windVol = 0.22f * s * s;
                    pool.setVolume(windStream, windVol, windVol);
                    pool.setRate(windStream, 0.8f + 0.6f * s);
                }
            }

            // 胎噪随打滑强度淡入淡出。
            if (skidId != 0) {
                if (skidStream == 0 && skid > 0.02f) {
                    skidStream = pool.play(skidId, 0.0f, 0.0f, 1, -1, 1f);
                }
                if (skidStream != 0) {
                    float skidVol = 0.35f * skid;
                    pool.setVolume(skidStream, skidVol, skidVol);
                }
            }
            if (braking) {
                playBrakeLocked();
            }
        }
    }

    /** 一次性胎噪脉冲，用于短促漂移。 */
    void playSkid() {
        synchronized (this) {
            if (ready && skidId != 0) {
                pool.play(skidId, 0.3f, 0.3f, 1, 0, 1f);
            }
        }
    }

    /** 刹车音效。 */
    void playBrake() {
        synchronized (this) {
            if (ready) {
                playBrakeLocked();
            }
        }
    }

    private void playBrakeLocked() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBrakeMs < 300L) {
            return;
        }
        lastBrakeMs = now;
        if (brakeId != 0) {
            pool.play(brakeId, 0.5f, 0.5f, 1, 0, 1f);
        }
    }

    /** 换挡音效；gear 为当前挡位，相同挡位不重复播放。 */
    void playGear(int gear) {
        synchronized (this) {
            if (gear == lastGear) {
                return;
            }
            lastGear = gear;
            if (ready && gearId != 0) {
                pool.play(gearId, 0.45f, 0.45f, 1, 0, 1f);
            }
        }
    }

    /** 倒计时提示音，供倒计时每秒调用。 */
    void playCountdown() {
        synchronized (this) {
            if (ready && countdownId != 0) {
                pool.play(countdownId, 0.7f, 0.7f, 1, 0, 1f);
            }
        }
    }

    /** 胜利音效。 */
    void playWin() {
        synchronized (this) {
            if (ready && winId != 0) {
                pool.play(winId, 0.7f, 0.7f, 1, 0, 1f);
            }
        }
    }

    /** 失败音效。 */
    void playLose() {
        synchronized (this) {
            if (ready && loseId != 0) {
                pool.play(loseId, 0.7f, 0.7f, 1, 0, 1f);
            }
        }
    }

    /** 开启森林环境鸟鸣循环；重复调用无副作用。 */
    void startAmbient() {
        synchronized (this) {
            if (!ready || pool == null || ambientStream != 0) {
                return;
            }
            int id = load("audio/ambient_birds.ogg");
            if (id != 0) {
                ambientStream = pool.play(id, AMBIENT_VOLUME, AMBIENT_VOLUME, 1, -1, 1f);
            }
        }
    }

    /** 停止环境鸟鸣。 */
    void stopAmbient() {
        synchronized (this) {
            if (ready && ambientStream != 0) {
                pool.stop(ambientStream);
                ambientStream = 0;
            }
        }
    }

    /** 切换到下一首音乐并立即播放（若音乐开启）。 */
    void nextTrack() {
        synchronized (this) {
            if (!ready) {
                return;
            }
            boolean wasPlaying = music != null && music.isPlaying();
            int next = (trackIndex + 1) % TRACKS.length;
            openMusic(next);
            if (wasPlaying && musicOn) {
                try { music.start(); } catch (Exception ignored) { }
            }
        }
    }

    /** 当前音乐索引，从 0 开始。 */
    int getTrackIndex() { synchronized (this) { return trackIndex; } }

    /** 音乐总数。 */
    int getTrackCount() { return TRACKS.length; }

    /** 提示音。 */
    void beep() {
        synchronized (this) {
            if (ready && beepId != 0) {
                pool.play(beepId, 0.7f, 0.7f, 1, 0, 1f);
            }
        }
    }

    /** 碰撞音效。 */
    void crash() {
        synchronized (this) {
            if (ready && crashId != 0) {
                pool.play(crashId, 0.6f, 0.6f, 1, 0, 1f);
            }
        }
    }

    /** 停止全部循环声并释放资源；可重复调用。 */
    void release() {
        synchronized (this) {
            if (music != null) {
                music.stop();
                music.release();
                music = null;
            }
            if (pool != null) {
                pool.release();
                pool = null;
            }
            engineLowStream = 0;
            engineHighStream = 0;
            skidStream = 0;
            windStream = 0;
            ambientStream = 0;
            lastGear = -1;
            lastBrakeMs = 0L;
            ready = false;
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
