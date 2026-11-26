package com.forest.racer;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** GLES3 资源工具：着色器、纹理、网格与网格构造器。 */
final class GLUtil {
    private GLUtil() {}

    static int program(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs);
        int f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("program link: " + GLES30.glGetProgramInfoLog(p));
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    static int textureFromAsset(AssetManager am, String path) {
        Bitmap bmp = null;
        try (InputStream in = am.open(path)) {
            bmp = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            // 素材缺失时回退到纯色纹理，避免崩溃
            bmp = solidBitmap(new int[]{110, 120, 105});
        }
        if (bmp == null) bmp = solidBitmap(new int[]{110, 120, 105});
        return textureFromBitmap(bmp);
    }

    private static Float maxAniso = null;

    static int textureFromBitmap(Bitmap bmp) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bmp.getWidth(), bmp.getHeight(),
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels(bmp));
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        // 各向异性过滤：斜视地面/赛道时显著提升清晰度
        if (maxAniso == null) {
            maxAniso = 0f;
            String exts = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            if (exts != null && exts.contains("GL_EXT_texture_filter_anisotropic")) {
                float[] v = new float[1];
                GLES30.glGetFloatv(0x84FF, v, 0);  // GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
                maxAniso = Math.min(8f, v[0]);
            }
        }
        if (maxAniso > 0f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 0x84FE, maxAniso); // GL_TEXTURE_MAX_ANISOTROPY_EXT
        return t[0];
    }

    private static java.nio.Buffer pixels(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] arr = new int[w * h];
        bmp.getPixels(arr, 0, w, 0, 0, w, h);
        return java.nio.IntBuffer.wrap(arr);
    }

    static Bitmap solidBitmap(int[] rgb) {
        Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        int[] px = new int[64];
        for (int i = 0; i < 64; i++) px[i] = 0xFF000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
        b.setPixels(px, 0, 8, 0, 0, 8, 8);
        return b;
    }

    static Bitmap noiseBitmap(int base, int spread, int seed) {
        java.util.Random r = new java.util.Random(seed);
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        int[] px = new int[64*64];
        for (int i = 0; i < px.length; i++) {
            int d = r.nextInt(spread*2+1) - spread;
            int rr = clamp255(base + d), gg = clamp255(base + d), bb = clamp255(base + d);
            px[i] = 0xFF000000 | (rr<<16) | (gg<<8) | bb;
        }
        b.setPixels(px, 0, 64, 0, 0, 64, 64);
        return b;
    }

    static Bitmap stripeBitmap(int[] a, int[] b, int stripes) {
        Bitmap bm = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        int[] px = new int[64*64];
        int band = 64 / stripes;
        for (int y = 0; y < 64; y++) {
            int[] c = ((y / band) % 2 == 0) ? a : b;
            for (int x = 0; x < 64; x++) {
                px[y*64+x] = 0xFF000000 | (c[0]<<16) | (c[1]<<8) | c[2];
            }
        }
        bm.setPixels(px, 0, 64, 0, 0, 64, 64);
        return bm;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** GPU 网格，支持索引绘制。 */
    static final class Mesh {
        final int vao, count;
        Mesh(int vao, int count) { this.vao = vao; this.count = count; }
        void draw() {
            GLES30.glBindVertexArray(vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0);
        }
    }

    /** 顶点数据构造器，按四边形累积，输出可直接上传的 Mesh。 */
    static final class MeshBuilder {
        private final List<Float> p = new ArrayList<>();
        private final List<Float> n = new ArrayList<>();
        private final List<Float> u = new ArrayList<>();
        private final List<Integer> idx = new ArrayList<>();

        void quad(float[] a, float[] b, float[] c, float[] d,
                  float u0, float v0, float u1, float v1) {
            float[] nn = Math3D.norm(Math3D.cross(Math3D.sub(b, a), Math3D.sub(d, a)));
            int base = p.size() / 3;
            add(a, nn, u0, v0); add(b, nn, u1, v0);
            add(c, nn, u1, v1); add(d, nn, u0, v1);
            idx.add(base); idx.add(base+1); idx.add(base+2);
            idx.add(base); idx.add(base+2); idx.add(base+3);
        }

        void quadUV(float[] a, float[] b, float[] c, float[] d,
                    float au, float av, float bu, float bv, float cu, float cv, float du, float dv) {
            float[] nn = Math3D.norm(Math3D.cross(Math3D.sub(b, a), Math3D.sub(d, a)));
            int base = p.size() / 3;
            add(a, nn, au, av); add(b, nn, bu, bv);
            add(c, nn, cu, cv); add(d, nn, du, dv);
            idx.add(base); idx.add(base+1); idx.add(base+2);
            idx.add(base); idx.add(base+2); idx.add(base+3);
        }

        private void add(float[] v, float[] nn, float uu, float vv) {
            p.add(v[0]); p.add(v[1]); p.add(v[2]);
            n.add(nn[0]); n.add(nn[1]); n.add(nn[2]);
            u.add(uu); u.add(vv);
        }

        void box(float x0, float x1, float y0, float y1, float z0, float z1, float us, float vs) {
            quad(new float[]{x0,y0,z1},new float[]{x1,y0,z1},new float[]{x1,y1,z1},new float[]{x0,y1,z1},0,0,us,vs);
            quad(new float[]{x1,y0,z0},new float[]{x0,y0,z0},new float[]{x0,y1,z0},new float[]{x1,y1,z0},0,0,us,vs);
            quad(new float[]{x1,y0,z1},new float[]{x1,y0,z0},new float[]{x1,y1,z0},new float[]{x1,y1,z1},0,0,us,vs);
            quad(new float[]{x0,y0,z0},new float[]{x0,y0,z1},new float[]{x0,y1,z1},new float[]{x0,y1,z0},0,0,us,vs);
            quad(new float[]{x0,y1,z1},new float[]{x1,y1,z1},new float[]{x1,y1,z0},new float[]{x0,y1,z0},0,0,us,vs);
            quad(new float[]{x0,y0,z0},new float[]{x1,y0,z0},new float[]{x1,y0,z1},new float[]{x0,y0,z1},0,0,us,vs);
        }

        /** 沿 Y 轴的圆柱，用于树干与车轮（车轮调用后自行旋转坐标）。 */
        void cylinderY(float cx, float cy, float cz, float radius, float height, int seg, float us, float vs) {
            for (int i = 0; i < seg; i++) {
                float a0 = 2f*(float)Math.PI*i/seg, a1 = 2f*(float)Math.PI*(i+1)/seg;
                float x0 = cx + radius*(float)Math.cos(a0), z0 = cz + radius*(float)Math.sin(a0);
                float x1 = cx + radius*(float)Math.cos(a1), z1 = cz + radius*(float)Math.sin(a1);
                quad(new float[]{x0,cy,z0}, new float[]{x1,cy,z1},
                     new float[]{x1,cy+height,z1}, new float[]{x0,cy+height,z0},
                     (float)i/seg*us, 0, (float)(i+1)/seg*us, vs);
            }
        }

        /** 沿 X 轴的圆柱（车轮轴），用于跑车轮胎。 */
        void cylinderX(float cx, float cy, float cz, float radius, float width, int seg) {
            for (int i = 0; i < seg; i++) {
                float a0 = 2f*(float)Math.PI*i/seg, a1 = 2f*(float)Math.PI*(i+1)/seg;
                float y0 = cy + radius*(float)Math.cos(a0), z0 = cz + radius*(float)Math.sin(a0);
                float y1 = cy + radius*(float)Math.cos(a1), z1 = cz + radius*(float)Math.sin(a1);
                quad(new float[]{cx-width/2,y0,z0}, new float[]{cx+width/2,y0,z0},
                     new float[]{cx+width/2,y1,z1}, new float[]{cx-width/2,y1,z1},
                     (float)i/seg, 0, (float)(i+1)/seg, 1);
            }
        }

        /** 三个交叉竖直平面，用作树冠，贴叶片纹理。 */
        void crown(float cx, float cy, float cz, float radius, float height) {
            for (int k = 0; k < 3; k++) {
                float a = (float) Math.PI * k / 3f;
                float dx = (float)Math.cos(a) * radius, dz = (float)Math.sin(a) * radius;
                float y0 = cy, y1 = cy + height;
                quad(new float[]{cx-dx,y0,cz-dz}, new float[]{cx+dx,y0,cz+dz},
                     new float[]{cx+dx,y1,cz+dz}, new float[]{cx-dx,y1,cz-dz},
                     0, 0, 1, 1);
            }
        }

        Mesh build() {
            int[] vao = new int[1];
            GLES30.glGenVertexArrays(1, vao, 0);
            GLES30.glBindVertexArray(vao[0]);
            int[] buf = new int[3];
            GLES30.glGenBuffers(3, buf, 0);
            upload(buf[0], 0, p, 3);
            upload(buf[1], 1, n, 3);
            upload(buf[2], 2, u, 2);
            int[] ib = new int[1];
            GLES30.glGenBuffers(1, ib, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0]);
            int[] ia = new int[idx.size()];
            for (int i = 0; i < ia.length; i++) ia[i] = idx.get(i);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ia.length*4,
                    java.nio.IntBuffer.wrap(ia), GLES30.GL_STATIC_DRAW);
            GLES30.glBindVertexArray(0);
            return new Mesh(vao[0], ia.length);
        }

        private static void upload(int buf, int loc, List<Float> data, int size) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
            float[] arr = new float[data.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = data.get(i);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, arr.length*4,
                    java.nio.FloatBuffer.wrap(arr), GLES30.GL_STATIC_DRAW);
            GLES30.glEnableVertexAttribArray(loc);
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, 0, 0);
        }
    }
}
