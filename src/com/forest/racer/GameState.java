package com.forest.racer;

import java.util.Arrays;

/** 比赛状态机：菜单 / 倒计时 / 比赛 / 结果。持有 6 辆车与排名。 */
final class GameState {
    enum Phase { MENU, COUNTDOWN, RACE, RESULT, ROAM }

    static final int CAR_COUNT = 6;
    Phase phase = Phase.MENU;
    Track track;
    Car[] cars = new Car[CAR_COUNT];
    int selectedTrack;
    float countdown;
    float raceTime;
    float lastLapStart;
    int finishPlace = 0;

    private final Car.Factory factory;
    private final AudioManager audio;

    GameState(Car.Factory factory, AudioManager audio) {
        this.factory = factory;
        this.audio = audio;
        selectTrack(0);
    }

    void selectTrack(int index) {
        this.selectedTrack = (int) Math3D.clamp(index, 0, Track.COUNT - 1);
        track = new Track(selectedTrack);
        for (int i = 0; i < CAR_COUNT; i++) {
            cars[i] = factory.create(track, i == 0, i, i);
        }
        phase = Phase.MENU;
        raceTime = 0; countdown = 0; finishPlace = 0;
    }

    void start() {
        for (int i = 0; i < CAR_COUNT; i++) cars[i].reset(i);
        phase = Phase.COUNTDOWN;
        countdown = 3.6f;
        raceTime = 0;
        finishPlace = 0;
        audio.startEngine();
    }

    void startRoam() {
        for (int i = 0; i < CAR_COUNT; i++) cars[i].reset(i);
        phase = Phase.ROAM;
        raceTime = 0;
        audio.startEngine();
    }

    void toMenu() {
        phase = Phase.MENU;
        audio.pauseMusic();
    }

    void update(float dt, float throttle, float brake, float steer) {
        if (phase == Phase.ROAM) {
            Car p = cars[0];
            p.updatePlayer(dt, throttle, brake, steer);
            int ng = gearOf(p.speed);
            audio.updateDrive(p.enginePitch, 0f, brake > 0.1f);
            return;
        }
        if (phase == Phase.COUNTDOWN) {
            float prev = countdown;
            countdown -= dt;
            if ((int) Math.ceil(prev) != (int) Math.ceil(countdown) && countdown > 0) audio.playCountdown();
            if (countdown <= 0) {
                phase = Phase.RACE;
                audio.playCountdown();
            }
            return;
        }
        if (phase != Phase.RACE) return;

        raceTime += dt;
        int oldGear = gearOf(cars[0].speed);
        cars[0].updatePlayer(dt, throttle, brake, steer);
        for (int i = 1; i < CAR_COUNT; i++) {
            cars[i].updateAI(dt, raceTime + i * 3f);
        }

        // 排名：按总进度降序（简单选择排序，6 辆车，避免 lambda 兼容问题）
        Car[] sorted = cars.clone();
        for (int i = 0; i < CAR_COUNT; i++) {
            int best = i;
            for (int j = i+1; j < CAR_COUNT; j++) {
                if (sorted[j].progress() > sorted[best].progress()) best = j;
            }
            Car tmp = sorted[i]; sorted[i] = sorted[best]; sorted[best] = tmp;
        }
        for (int i = 0; i < CAR_COUNT; i++) sorted[i].place = i + 1;

        Car p = cars[0];
        if (p.lap > 0 && p.lapTime == 0) p.lapTime = raceTime;
        if (p.lap >= 3 && finishPlace == 0) {
            finishPlace = p.place;
            phase = Phase.RESULT;
            if (p.place <= 3) audio.playWin(); else audio.playLose();
        }
        // 挡位变化音效
        int newGear = gearOf(p.speed);
        if (newGear != oldGear) audio.playGear(newGear);
        // 引擎分层 + 胎噪 + 风噪 + 刹车
        float skid = Math.min(1f, Math.abs(p.lateral) * 0.5f + Math.abs(p.steer) * p.speed / 90f);
        audio.updateDrive(p.enginePitch, skid, brake > 0.1f);
    }

    private static int gearOf(float speed) {
        if (speed < 8) return 1;
        if (speed < 18) return 2;
        if (speed < 30) return 3;
        if (speed < 42) return 4;
        if (speed < 55) return 5;
        return 6;
    }

    String hudText() {
        Car p = cars[0];
        float kmh = p.speed * 3.6f;
        StringBuilder s = new StringBuilder();
        s.append("赛道 ").append(selectedTrack + 1).append("/21  ").append(track.name).append('\n');
        if (phase == Phase.ROAM) {
            s.append("自由漫游\n");
        } else {
            s.append("名次 ").append(p.place).append("/6   圈数 ")
             .append(Math.min(p.lap + 1, 3)).append("/3\n");
        }
        s.append(String.format("%03.0f km/h", kmh));
        if (phase == Phase.RACE) {
            s.append("   时间 ").append(String.format("%.1f", raceTime)).append(" s");
        }
        return s.toString();
    }

    String countdownText() {
        if (phase == Phase.ROAM) return "自由漫游";
        if (phase == Phase.COUNTDOWN) {
            int n = (int) Math.ceil(countdown);
            return n > 0 ? String.valueOf(n) : "GO";
        }
        if (phase == Phase.RESULT) {
            return "完赛  名次 " + finishPlace + "/6\n用时 " + String.format("%.1f", raceTime) + " s";
        }
        return "";
    }
}
