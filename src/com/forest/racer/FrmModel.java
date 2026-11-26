package com.forest.racer;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FRM1 高模加载器：加载 vinish.dev CC BY 4.0 高模跑车（13-17 万面，PBR）。
 * 每个节点（车身/四轮）按材质分组绘制，保留全部高模几何细节。
 */
final class FrmModel {
    static final class Material {
        float[] base = new float[4];
        float metallic, roughness;
        int baseTex = -1, normalTex = -1;
        boolean hasUV;
    }

    static final class Prim {
        GLUtil.Mesh mesh;
        Material mat;
        Prim(GLUtil.Mesh m, Material t) { mesh = m; mat = t; }
    }

    static final class Node {
        String name;
        float[] matrix = Math3D.identity();
        List<Prim> prims = new ArrayList<>();
        /** 节点在模型局部空间的包围盒中心，用于对齐车身与车轮 */
        float[] center = new float[3];
    }

    final List<Node> nodes = new ArrayList<>();
    final List<Material> materials = new ArrayList<>();
    final List<Integer> textures = new ArrayList<>();
    static final AssetManager ASSET_HOLDER = null; // 占位，避免静态持有

    static FrmModel load(AssetManager am, String path) {
        byte[] raw = readAsset(am, path);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("FRM1")) throw new RuntimeException("bad FRM: " + path);
        int version = bb.getInt();
        int matCount = bb.getInt();
        int texCount = bb.getInt();
        int nodeCount = bb.getInt();

        FrmModel model = new FrmModel();

        // 材质
        for (int i = 0; i < matCount; i++) {
            Material m = new Material();
            for (int k = 0; k < 4; k++) m.base[k] = bb.getFloat();
            m.metallic = bb.getFloat();
            m.roughness = bb.getFloat();
            m.baseTex = bb.getInt();
            m.normalTex = bb.getInt();
            m.hasUV = bb.getInt() != 0;
            model.materials.add(m);
        }

        // 贴图：保留压缩字节，BitmapFactory 解码
        for (int i = 0; i < texCount; i++) {
            int w = bb.getInt(), h = bb.getInt(), len = bb.getInt(), tag = bb.getInt();
            byte[] img = new byte[len];
            bb.get(img);
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, len);
            if (bmp == null) bmp = GLUtil.solidBitmap(new int[]{128,128,128});
            model.textures.add(GLUtil.textureFromBitmap(bmp));
        }

        // 节点
        for (int ni = 0; ni < nodeCount; ni++) {
            Node node = new Node();
            int nameLen = bb.getInt();
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            node.name = new String(nameBytes);
            for (int k = 0; k < 16; k++) node.matrix[k] = bb.getFloat();
            int primCount = bb.getInt();
            float[] min = {Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
            for (int pi = 0; pi < primCount; pi++) {
                int vc = bb.getInt(), ic = bb.getInt(), mid = bb.getInt(), mid2 = bb.getInt();
                int baseTex = bb.getInt(), normTex = bb.getInt();
                float[] verts = new float[vc * 8];
                for (int k = 0; k < verts.length; k++) verts[k] = bb.getFloat();
                int[] idx = new int[ic];
                for (int k = 0; k < ic; k++) idx[k] = bb.getInt();
                for (int k = 0; k < vc; k++) {
                    for (int d = 0; d < 3; d++) {
                        float v = verts[k*8+d];
                        if (v < min[d]) min[d] = v;
                        if (v > max[d]) max[d] = v;
                    }
                }
                GLUtil.Mesh mesh = buildMesh(verts, idx);
                Material mat = model.materials.get(mid);
                // 运行时贴图索引覆盖（若 prim 指定）
                if (baseTex >= 0 || normTex >= 0) {
                    mat = new Material();
                    Material src = model.materials.get(mid);
                    System.arraycopy(src.base, 0, mat.base, 0, 4);
                    mat.metallic = src.metallic; mat.roughness = src.roughness;
                    mat.hasUV = src.hasUV;
                    mat.baseTex = baseTex; mat.normalTex = normTex;
                }
                node.prims.add(new Prim(mesh, mat));
            }
            for (int d = 0; d < 3; d++) node.center[d] = (min[d] + max[d]) * 0.5f;
            model.nodes.add(node);
        }
        return model;
    }

    private static GLUtil.Mesh buildMesh(float[] verts, int[] idx) {
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        GLES30.glBindVertexArray(vao[0]);
        int[] buf = new int[3];
        GLES30.glGenBuffers(3, buf, 0);
        int vc = verts.length / 8;
        float[] pos = new float[vc*3], nor = new float[vc*3], uv = new float[vc*2];
        for (int i = 0; i < vc; i++) {
            pos[i*3] = verts[i*8]; pos[i*3+1] = verts[i*8+1]; pos[i*3+2] = verts[i*8+2];
            nor[i*3] = verts[i*8+3]; nor[i*3+1] = verts[i*8+4]; nor[i*3+2] = verts[i*8+5];
            uv[i*2] = verts[i*8+6]; uv[i*2+1] = verts[i*8+7];
        }
        upload(buf[0], 0, pos, 3);
        upload(buf[1], 1, nor, 3);
        upload(buf[2], 2, uv, 2);
        int[] ib = new int[1];
        GLES30.glGenBuffers(1, ib, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0]);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length*4,
                IntBuffer.wrap(idx), GLES30.GL_STATIC_DRAW);
        GLES30.glBindVertexArray(0);
        return new GLUtil.Mesh(vao[0], idx.length);
    }

    private static void upload(int buf, int loc, float[] arr, int size) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, arr.length*4,
                ByteBuffer.wrap(toBytes(arr)).order(ByteOrder.LITTLE_ENDIAN), GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, 0, 0);
    }

    private static byte[] toBytes(float[] arr) {
        ByteBuffer bb = ByteBuffer.allocate(arr.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : arr) bb.putFloat(f);
        return bb.array();
    }

    private static byte[] readAsset(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("read asset failed: " + path, e);
        }
    }
}
