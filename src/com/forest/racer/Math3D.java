package com.forest.racer;

/** 轻量 3D 数学库，列主序矩阵，与 OpenGL ES 一致。单位：米、弧度。 */
final class Math3D {
    private Math3D() {}

    static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    /** 返回 a*b，两者均为列主序 4x4。 */
    static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k*4+r] * b[c*4+k];
                o[c*4+r] = s;
            }
        }
        return o;
    }

    static float[] perspective(float fovy, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(fovy / 2f);
        float[] m = new float[16];
        m[0] = f / aspect; m[5] = f;
        m[10] = (far + near) / (near - far); m[11] = -1;
        m[14] = 2f * far * near / (near - far);
        return m;
    }

    static float[] ortho(float l, float r, float b, float t, float n, float f) {
        float[] m = new float[16];
        m[0] = 2f/(r-l); m[5] = 2f/(t-b); m[10] = -2f/(f-n);
        m[12] = -(r+l)/(r-l); m[13] = -(t+b)/(t-b); m[14] = -(f+n)/(f-n);
        m[15] = 1;
        return m;
    }

    static float[] lookAt(float[] eye, float[] center, float[] up) {
        float[] z = norm(sub(eye, center));
        float[] x = norm(cross(up, z));
        float[] y = cross(z, x);
        return new float[]{
            x[0], y[0], z[0], 0,
            x[1], y[1], z[1], 0,
            x[2], y[2], z[2], 0,
            -dot(x, eye), -dot(y, eye), -dot(z, eye), 1
        };
    }

    static float[] trs(float tx, float ty, float tz, float yaw, float sx, float sy, float sz) {
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        // 列主序：Y 旋转后缩放，再平移
        return new float[]{
            c*sx, 0, -s*sx, 0,
            0, sy, 0, 0,
            s*sz, 0, c*sz, 0,
            tx, ty, tz, 1
        };
    }

    static float[] sub(float[] a, float[] b) {
        return new float[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]};
    }

    static float[] add(float[] a, float[] b) {
        return new float[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]};
    }

    static float[] scale(float[] a, float s) {
        return new float[]{a[0]*s, a[1]*s, a[2]*s};
    }

    static float dot(float[] a, float[] b) {
        return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]};
    }

    static float[] norm(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        if (l < 1e-8f) return new float[]{0,1,0};
        return new float[]{a[0]/l, a[1]/l, a[2]/l};
    }

    static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, a[2]+(b[2]-a[2])*t};
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
