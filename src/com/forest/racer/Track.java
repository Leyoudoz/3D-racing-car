package com.forest.racer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 程序化赛道：21 条固定种子的闭合样条，含中心线、宽度、高度与树木布置。
 * 同一索引每次生成形状完全一致，单位米。
 */
final class Track {
    static final int COUNT = 21;
    final int index;
    final String name;
    final float[] cx;      // 中心线 X
    final float[] cy;      // 中心线高度 Y
    final float[] cz;      // 中心线 Z
    final float[] widths;  // 各点半宽
    final float[] trees;   // 树木位置 (x,y,z,scale,yaw) * N
    final int treeCount;
    final float totalLength;

    static final String[] NAMES = {
        "晨雾松林", "翡翠弯道", "落叶回廊", "溪流长道", "云杉山径",
        "苔石峡谷", "古树环道", "薄暮林海", "橡木大道", "枫红回旋",
        "静谷幽径", "白桦长廊", "雾岭盘山", "林间八角", "碧潭弯道",
        "松涛急转", "幽篁曲径", "荒野林道", "月影树林", "杉林回旋", "终章环路"
    };

    Track(int index) {
        if (index < 0 || index >= COUNT) throw new IllegalArgumentException("track index: " + index);
        this.index = index;
        this.name = NAMES[index];
        Random r = new Random(1000L + index * 7919L);

        int n = 96;
        float baseR = 95f + r.nextFloat() * 55f;
        float wobble1 = 0.12f + r.nextFloat() * 0.22f;
        float wobble2 = 0.05f + r.nextFloat() * 0.15f;
        int harmonic1 = 2 + r.nextInt(4);
        int harmonic2 = 5 + r.nextInt(5);
        float phase1 = r.nextFloat() * 6.28f;
        float phase2 = r.nextFloat() * 6.28f;
        float halfW = 6.5f + r.nextFloat() * 2.5f;
        float hillAmp = 2.5f + r.nextFloat() * 5f;
        int hillFreq = 1 + r.nextInt(3);

        cx = new float[n]; cy = new float[n]; cz = new float[n]; widths = new float[n];
        for (int i = 0; i < n; i++) {
            float a = 2f * (float) Math.PI * i / n;
            float rad = baseR * (1f + wobble1 * (float) Math.sin(harmonic1*a + phase1)
                                   + wobble2 * (float) Math.cos(harmonic2*a + phase2));
            cx[i] = (float) Math.cos(a) * rad;
            cz[i] = (float) Math.sin(a) * rad;
            cy[i] = hillAmp * (float) Math.sin(hillFreq*a + phase1*0.5f)
                    + 1.5f * (float) Math.sin(3*a + phase2);
            widths[i] = halfW * (0.9f + 0.2f * (float) Math.sin(3*a + phase1));
        }

        float len = 0;
        for (int i = 0; i < n; i++) {
            int j = (i+1) % n;
            len += (float) Math.hypot(cx[j]-cx[i], cz[j]-cz[i]);
        }
        totalLength = len;

        // 树木：沿中心线两侧散布，避开路面
        List<Float> tr = new ArrayList<>();
        int clusters = 260 + r.nextInt(180);
        for (int i = 0; i < clusters; i++) {
            float a = r.nextFloat() * 2f * (float) Math.PI;
            float rad = baseR * (1f + wobble1*(float)Math.sin(harmonic1*a+phase1)
                                   + wobble2*(float)Math.cos(harmonic2*a+phase2));
            float px = (float)Math.cos(a)*rad;
            float pz = (float)Math.sin(a)*rad;
            float py = hillAmp*(float)Math.sin(hillFreq*a+phase1*0.5f)
                     + 1.5f*(float)Math.sin(3*a+phase2);
            // 法线方向偏移
            float nx = (float)Math.cos(a), nz = (float)Math.sin(a);
            float offset = halfW + 3f + r.nextFloat() * 45f;
            float side = r.nextBoolean() ? 1f : -1f;
            px += nx*offset*side + (r.nextFloat()-0.5f)*8f;
            pz += nz*offset*side + (r.nextFloat()-0.5f)*8f;
            float scale = 0.7f + r.nextFloat()*1.6f;
            tr.add(px); tr.add(py); tr.add(pz); tr.add(scale); tr.add(r.nextFloat()*6.28f);
        }
        trees = new float[tr.size()];
        for (int i = 0; i < trees.length; i++) trees[i] = tr.get(i);
        treeCount = trees.length / 5;
    }

    int size() { return cx.length; }

    /** 按参数 t（0..size）取中心线插值点与朝向。 */
    float[] pointAt(float t) {
        int n = size();
        int i = ((int) Math.floor(t)) % n;
        if (i < 0) i += n;
        int j = (i+1) % n;
        float f = t - (float) Math.floor(t);
        return new float[]{
            cx[i]+(cx[j]-cx[i])*f,
            cy[i]+(cy[j]-cy[i])*f,
            cz[i]+(cz[j]-cz[i])*f,
            widths[i]+(widths[j]-widths[i])*f
        };
    }

    /** 赛道朝向角（绕 Y），用于车辆对齐。 */
    float yawAt(float t) {
        float[] a = pointAt(t), b = pointAt(t+0.5f);
        return (float) Math.atan2(b[0]-a[0], b[2]-a[2]);
    }
}
