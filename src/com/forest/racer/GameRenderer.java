package com.forest.racer;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES3 渲染器：阴影映射 + 纹理法线光照 + 雾。
 * 静态赛道与树木烘焙为网格一次绘制，动态车辆单独绘制。
 */
final class GameRenderer implements GLSurfaceView.Renderer, Car.Factory {

    interface HudCallback {
        void onHud(String text, String center, boolean racing);
        void onLoadProgress(int percent, String message);
        void onLoaded();
    }

    private final Context ctx;
    private final HudCallback hud;
    private final AudioManager audio;
    final GameState state;

    private int mainProg, shadowProg, carProg, skyProg;
    private boolean lowQuality = false;   // SwiftShader 软件渲染时自动降级
    private int skyTex;
    private FrmModel[] natureModels = new FrmModel[5];   // 真实树木/灌木/岩石
    private FrmModel[] propModels = new FrmModel[2];     // 护栏/路灯
    private FrmModel[] carModels = new FrmModel[3];
    private FrmModel[] carModelsLod = new FrmModel[3];
    private PostFX postFX;
    private int viewW, viewH;
    private int texRoad, texRoadN, texRoadR;
    private int texGrass, texGrassN, texGrassR;
    private int texBark, texBarkN, texBarkR;
    private int texLeaf, texLeafN, texLeafR;
    private int texRock, texRockN, texRockR;
    private int texLine, texCarBody, texCarGlass;
    private int[] carColorTex = new int[6];

    private GLUtil.Mesh roadMesh, shoulderMesh, lineMesh, groundMesh;
    private GLUtil.Mesh trunkMesh, crownMesh, rockMesh;
    // 真实树木/道具实例: [x,y,z,scale,yaw,modelIndex] * N
    private float[] scenery;
    private int sceneryCount;
    private GLUtil.Mesh bodyMesh, wheelMesh;
    private GLUtil.Mesh skyMesh;

    private int shadowFbo, shadowTex;
    private static final int SHADOW_SIZE = 2048;

    private float aspect = 1f;
    private long lastNano = 0;
    private float throttle, brake, steer;
    private final float[] lightDir = Math3D.norm(new float[]{-0.45f, -0.82f, -0.35f});

    GameRenderer(Context ctx, HudCallback hud, AudioManager audio) {
        this.ctx = ctx;
        this.hud = hud;
        this.audio = audio;
        this.state = new GameState(this, audio);
    }

    void setInput(float throttle, float brake, float steer) {
        this.throttle = throttle; this.brake = brake; this.steer = steer;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glClearColor(0.62f, 0.76f, 0.90f, 1f);

        String rendererName = GLES30.glGetString(GLES30.GL_RENDERER);
        String vendorName = GLES30.glGetString(GLES30.GL_VENDOR);
        boolean softwareRenderer = rendererName != null &&
                (rendererName.toLowerCase().contains("swiftshader") || rendererName.toLowerCase().contains("software")
                 || rendererName.toLowerCase().contains("llvmpipe"));
        lowQuality = softwareRenderer;
        android.util.Log.d("FRgpu", "renderer=" + rendererName + " vendor=" + vendorName
                + " software=" + softwareRenderer + " lowQuality=" + lowQuality);

        hud.onLoadProgress(10, "初始化渲染管线");
        mainProg = GLUtil.program(MAIN_VS, MAIN_FS);
        shadowProg = GLUtil.program(SHADOW_VS, SHADOW_FS);
        carProg = GLUtil.program(CAR_VS, CAR_FS);
        skyProg = GLUtil.program(SKY_VS, SKY_FS);
        skyTex = t("textures/sky.jpg");

        hud.onLoadProgress(25, "加载高模跑车");
        // 加载 3 台 CC BY 4.0 高模超跑（13-17 万面，PBR 贴图）
        carModels[0] = FrmModel.load(ctx.getAssets(), "models/mid-engine-supercar.frm");
        carModels[1] = FrmModel.load(ctx.getAssets(), "models/rear-engine-sports-coupe.frm");
        carModels[2] = FrmModel.load(ctx.getAssets(), "models/grand-tourer.frm");
        hud.onLoadProgress(45, "加载优化模型");
        // 中模 LOD（降面保留外形，软件渲染用）
        carModelsLod[0] = FrmModel.load(ctx.getAssets(), "models/mid-engine-supercar_lod.frm");
        carModelsLod[1] = FrmModel.load(ctx.getAssets(), "models/rear-engine-sports-coupe_lod.frm");
        carModelsLod[2] = FrmModel.load(ctx.getAssets(), "models/grand-tourer_lod.frm");
        hud.onLoadProgress(65, "加载森林植被与贴图");
        // 加载 CC0 真实树木/灌木/岩石 + 路边道具
        String[] nature = {"TreeLow001","TreeMed001","TreeHigh001","Bush001","Rock001"};
        for (int i = 0; i < nature.length; i++) {
            natureModels[i] = FrmModel.load(ctx.getAssets(), "models/nature/" + nature[i] + ".frm");
        }
        propModels[0] = FrmModel.load(ctx.getAssets(), "models/props/concrete_road_barrier.frm");
        propModels[1] = FrmModel.load(ctx.getAssets(), "models/props/street_lamp_01.frm");

        texRoad = t("textures/asphalt_01_Diffuse.jpg");
        texRoadN = t("textures/asphalt_01_nor_gl.jpg");
        texRoadR = t("textures/asphalt_01_Rough.jpg");
        texGrass = t("textures/grass_green.jpg");
        texGrassN = t("textures/grass_ground_nor_gl.jpg");
        texGrassR = t("textures/grass_ground_Rough.jpg");
        texBark = t("textures/bark_brown_02_Diffuse.jpg");
        texBarkN = t("textures/bark_brown_02_nor_gl.jpg");
        texBarkR = t("textures/bark_brown_02_Rough.jpg");
        texLeaf = t("textures/forest_leaves_02_Diffuse.jpg");
        texLeafN = t("textures/forest_leaves_02_nor_gl.jpg");
        texLeafR = t("textures/forest_leaves_02_Rough.jpg");
        texRock = t("textures/aerial_rocks_02_Diffuse.jpg");
        texRockN = t("textures/aerial_rocks_02_nor_gl.jpg");
        texRockR = t("textures/aerial_rocks_02_Rough.jpg");
        texLine = GLUtil.textureFromBitmap(GLUtil.solidBitmap(new int[]{235, 238, 232}));
        texCarGlass = GLUtil.textureFromBitmap(GLUtil.solidBitmap(new int[]{28, 42, 56}));
        int[] colors = {
            0xD8DCE0, 0xC0392B, 0x1F6FB2, 0xE1B12C, 0x27AE60, 0x8E44AD
        };
        for (int i = 0; i < 6; i++) {
            int[] c = {(colors[i]>>16)&255, (colors[i]>>8)&255, colors[i]&255};
            carColorTex[i] = GLUtil.textureFromBitmap(GLUtil.stripeBitmap(c, c, 1));
        }
        texCarBody = carColorTex[0];

        bodyMesh = buildCarBody();
        wheelMesh = buildWheel();
        skyMesh = buildSky();
        buildShadowFbo();
        // 网格就绪后重建全部车辆（GameState 构造时网格尚未创建）
        state.selectTrack(state.selectedTrack);
        rebuildTrack(state.track);
        lastNano = System.nanoTime();
        hud.onLoadProgress(100, "准备就绪");
        hud.onLoaded();
    }

    private int t(String p) { return GLUtil.textureFromAsset(ctx.getAssets(), p); }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES30.glViewport(0, 0, w, h);
        aspect = (float) w / Math.max(1, h);
        viewW = w; viewH = h;
        postFX = new PostFX();
        // 软件渲染时用半分辨率 FBO（像素成本降为1/4），composite 输出到全屏
        int rw = lowQuality ? Math.max(2, w/2) : w;
        int rh = lowQuality ? Math.max(2, h/2) : h;
        postFX.init(rw, rh);
    }

    /** 切换赛道时重建静态几何；先释放旧 GPU 网格，避免显存泄漏导致后续赛道渲染异常。 */
    void rebuildTrack(Track tr) {
        if (roadMesh == null && tr == null) return;
        deleteMesh(roadMesh); deleteMesh(shoulderMesh); deleteMesh(lineMesh);
        deleteMesh(groundMesh); deleteMesh(trunkMesh); deleteMesh(crownMesh);
        deleteMesh(rockMesh);
        // glDelete 异步执行：立即 glGen 可能复用同一 GPU 句柄导致新网格被误删。
        // 同步等待删除完成后再重建。
        GLES30.glFinish();
        roadMesh = shoulderMesh = lineMesh = groundMesh = null;
        trunkMesh = crownMesh = rockMesh = null;
        buildTrackMeshes(tr);
        buildForestMeshes(tr);
    }

    private static void deleteMesh(GLUtil.Mesh m) {
        if (m != null) m.delete();
    }

    private void buildTrackMeshes(Track tr) {
        GLUtil.MeshBuilder road = new GLUtil.MeshBuilder();
        GLUtil.MeshBuilder shoulder = new GLUtil.MeshBuilder();
        GLUtil.MeshBuilder line = new GLUtil.MeshBuilder();
        int n = tr.size();
        float dist = 0;
        for (int i = 0; i < n; i++) {
            int j = (i+1) % n;
            float[] a = tr.pointAt(i), b = tr.pointAt(j);
            float len = (float) Math.hypot(b[0]-a[0], b[2]-a[2]);
            float yawA = tr.yawAt(i), yawB = tr.yawAt(j);
            float[] na = {-(float)Math.cos(yawA), 0, (float)Math.sin(yawA)};
            float[] nb = {-(float)Math.cos(yawB), 0, (float)Math.sin(yawB)};
            float wa = a[3], wb = b[3];
            float u0 = dist/7f, u1 = (dist+len)/7f;

            // 路面
            road.quadUV(
                p(a,na,wa), p(b,nb,wb), p(b,nb,-wb), p(a,na,-wa),
                u0,0, u1,0, u1,1, u0,1);
            // 两侧路肩/草地
            shoulder.quadUV(p(a,na,wa+6), p(b,nb,wb+6), p(b,nb,wb), p(a,na,wa),
                u0/4,0, u1/4,0, u1/4,1, u0/4,1);
            shoulder.quadUV(p(b,nb,-wb), p(a,na,-wa), p(a,na,-wa-6), p(b,nb,-wb-6),
                u1/4,0, u0/4,0, u0/4,1, u1/4,1);
            // 中央虚线
            if (i % 2 == 0) {
                line.quad(p(a,na,0.18f), p(b,nb,0.18f), p(b,nb,-0.18f), p(a,na,-0.18f), 0,0,1,1);
            }
            dist += len;
        }
        roadMesh = road.build();
        shoulderMesh = shoulder.build();
        lineMesh = line.build();

        // 大面积森林地形：高度跟随赛道，避免赛道低于地面被覆盖（近一半赛道曾沉入地下）
        GLUtil.MeshBuilder ground = new GLUtil.MeshBuilder();
        float R = 340f;
        int seg = 96;
        float cell = 2f*R/seg;
        float[][] gh = new float[seg+1][seg+1];
        for (int i = 0; i <= seg; i++) {
            for (int j = 0; j <= seg; j++) {
                float x = -R + cell*j, z = -R + cell*i;
                gh[i][j] = terrainHeight(tr, x, z);
            }
        }
        for (int i = 0; i < seg; i++) {
            for (int j = 0; j < seg; j++) {
                float x0 = -R + cell*j, x1 = x0 + cell;
                float z0 = -R + cell*i, z1 = z0 + cell;
                ground.quadUV(
                    new float[]{x0,gh[i][j],z0}, new float[]{x0,gh[i][j+1],z1},
                    new float[]{x1,gh[i+1][j+1],z1}, new float[]{x1,gh[i+1][j],z0},
                    x0/14f, z0/14f, x0/14f, z1/14f, x1/14f, z1/14f, x1/14f, z0/14f);
            }
        }
        groundMesh = ground.build();
    }

    private static float[] p(float[] c, float[] n, float off) {
        return new float[]{c[0]+n[0]*off, c[1]+0.06f, c[2]+n[2]*off};
    }

    /** 地形高度：靠近赛道处贴合赛道高度，远处平滑过渡到基底，避免赛道悬空或沉入。 */
    private static float terrainHeight(Track tr, float x, float z) {
        float bestD = Float.MAX_VALUE, bestY = 0f;
        for (int i = 0; i < tr.size(); i += 2) {
            float dx = tr.cx[i]-x, dz = tr.cz[i]-z;
            float d = dx*dx + dz*dz;
            if (d < bestD) { bestD = d; bestY = tr.cy[i]; }
        }
        float dist = (float)Math.sqrt(bestD);
        // 赛道附近地形压低 0.8m，配合较高网格分辨率避免穿透路面
        float blend = 1f - Math3D.clamp((dist - 14f)/50f, 0f, 1f);
        return -1.2f + (bestY + 1.2f) * blend - 0.8f * blend;
    }

    private void buildForestMeshes(Track tr) {
        java.util.Random r = new java.util.Random(9000 + tr.index);
        // 真实 CC0 树木/岩石实例替换原十字面片：[x,y,z,scale,yaw,modelIndex]
        java.util.List<Float> sc = new java.util.ArrayList<>();
        // Gobkit 模型原始尺寸是几百单位（TreeHigh 高 849、Rock 宽 468），需缩小到米级
        float[] modelHeight = {432f, 596f, 849f, 140f, 213f}; // 对应 5 个 nature 模型
        float[] targetHeight = {9f, 12f, 15f, 2.5f, 2.2f};    // 目标米制高度
        for (int i = 0; i < tr.treeCount; i++) {
            float x = tr.trees[i*5], z = tr.trees[i*5+2];
            float y = terrainHeight(tr, x, z);
            int modelIdx = i % 5;                        // 3 种树 + 灌木 + 岩石
            if (i % 11 == 0) modelIdx = 4;               // 少量岩石
            float variation = 0.8f + tr.trees[i*5+3] * 0.25f;
            float s = targetHeight[modelIdx] / modelHeight[modelIdx] * variation;
            sc.add(x); sc.add(y); sc.add(z); sc.add(s);
            sc.add(tr.trees[i*5+4]); sc.add((float) modelIdx);
        }
        // 路边护栏与路灯（真实道具）
        int props = 40;
        for (int i = 0; i < props; i++) {
            float t = (float) i / props * tr.size();
            float[] pt = tr.pointAt(t);
            float n[] = {-(float)Math.cos(tr.yawAt(t)), 0, (float)Math.sin(tr.yawAt(t))};
            float side = (i % 2 == 0) ? 1f : -1f;
            float off = pt[3] + 1.6f;
            float px = pt[0] + n[0]*off*side, pz = pt[2] + n[2]*off*side;
            float py = terrainHeight(tr, px, pz);
            boolean lamp = (i % 8 == 0);
            sc.add(px); sc.add(py); sc.add(pz);
            sc.add(1.0f);              // 道具本身已是米制
            sc.add(tr.yawAt(t));
            sc.add(lamp ? 6f : 5f);   // 5=护栏, 6=路灯
        }
        scenery = new float[sc.size()];
        for (int i = 0; i < scenery.length; i++) scenery[i] = sc.get(i);
        sceneryCount = scenery.length / 6;
        // 保留一个简化远景树带（极远处填充地平线，避免空旷）
        GLUtil.MeshBuilder far = new GLUtil.MeshBuilder();
        for (int i = 0; i < tr.treeCount; i += 4) {
            float x = tr.trees[i*5], z = tr.trees[i*5+2];
            float y = terrainHeight(tr, x, z);
            float s = tr.trees[i*5+3];
            far.cylinderY(x, y, z, 0.4f*s, 8f*s, 5, 1, 2);
        }
        trunkMesh = far.build();
        crownMesh = null;
        rockMesh = null;
    }

    private GLUtil.Mesh buildCarBody() {
        GLUtil.MeshBuilder m = new GLUtil.MeshBuilder();
        // 跑车低矮楔形车身：底盘、引擎盖、座舱、尾翼，程序化生成
        m.box(-2.2f, 2.2f, 0.35f, 0.78f, -0.92f, 0.92f, 2, 1);
        m.box(-1.0f, 0.9f, 0.78f, 1.24f, -0.78f, 0.78f, 1.5f, 1);   // 座舱外壳
        m.box(0.9f, 2.1f, 0.78f, 0.92f, -0.88f, 0.88f, 1, 1);       // 引擎盖
        m.box(-2.2f, -1.95f, 0.92f, 1.28f, -0.86f, 0.86f, 1, 1);    // 尾翼支架上沿
        // 侧裙
        m.box(-1.6f, 1.6f, 0.18f, 0.36f, -0.98f, -0.78f, 1, 1);
        m.box(-1.6f, 1.6f, 0.18f, 0.36f, 0.78f, 0.98f, 1, 1);
        return m.build();
    }

    private GLUtil.Mesh buildWheel() {
        GLUtil.MeshBuilder m = new GLUtil.MeshBuilder();
        m.cylinderX(0, 0, 0, 0.44f, 0.3f, 12);
        return m.build();
    }

    private GLUtil.Mesh buildSky() {
        GLUtil.MeshBuilder m = new GLUtil.MeshBuilder();
        // 天空球（内侧可见）：equirectangular 全景图按经纬度映射 UV
        float R = 420f;
        int lonSeg = 48, latSeg = 24;
        for (int la = 0; la < latSeg; la++) {
            float t0 = (float) Math.PI * la / latSeg;      // 0=顶, PI=底
            float t1 = (float) Math.PI * (la+1) / latSeg;
            for (int lo = 0; lo < lonSeg; lo++) {
                float p0 = 2f*(float)Math.PI*lo/lonSeg;
                float p1 = 2f*(float)Math.PI*(lo+1)/lonSeg;
                float[] a = sphere(R, t0, p0), b = sphere(R, t1, p0);
                float[] c = sphere(R, t1, p1), d = sphere(R, t0, p1);
                float u0 = lo/(float)lonSeg, u1 = (lo+1)/(float)lonSeg;
                float v0 = la/(float)latSeg, v1 = (la+1)/(float)latSeg;
                m.quadUV(d, c, b, a, u0, v0, u1, v0, u1, v1, u0, v1);
            }
        }
        return m.build();
    }

    private static float[] sphere(float r, float t, float p) {
        return new float[]{
            (float)(r * Math.sin(t) * Math.cos(p)),
            (float)(r * Math.cos(t)),
            (float)(r * Math.sin(t) * Math.sin(p))
        };
    }

    private void buildShadowFbo() {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        shadowTex = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24,
                SHADOW_SIZE, SHADOW_SIZE, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL);
        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        shadowFbo = fbo[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D, shadowTex, 0);
        GLES30.glDrawBuffers(1, java.nio.IntBuffer.wrap(new int[]{GLES30.GL_NONE}));
        GLES30.glReadBuffer(GLES30.GL_NONE);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1e9f;
        lastNano = now;
        if (dt > 0.05f) dt = 0.05f;
        if (state.phase != GameState.Phase.MENU) state.update(dt, throttle, brake, steer);

        Car p = state.cars[0];
        float[] camTarget = {p.x, p.y + 1.2f, p.z};
        float fx = (float)Math.sin(p.yaw), fz = (float)Math.cos(p.yaw);
        // 相机在车后方，紧跟车辆实际位置与朝向（转向时不脱节）
        float camX = p.x - fx*8.5f, camZ = p.z - fz*8.5f;
        // 相机不得钻入地形：按相机脚下地形高度抬升（不同赛道地形起伏不同）
        float groundY = terrainHeight(state.track, camX, camZ);
        float camY = Math.max(p.y + 3.6f, groundY + 2.0f);
        float[] camPos = {camX, camY, camZ};

        float[] up = {0,1,0};
        float[] view = Math3D.lookAt(camPos, camTarget, up);
        float[] proj = Math3D.perspective((float)Math.toRadians(62), aspect, 0.3f, 500f);
        float[] vp = Math3D.mul(proj, view);

        // 光源视图投影（跟随玩家）
        float[] lightPos = Math3D.add(camTarget, Math3D.scale(lightDir, -120f));
        float[] lightView = Math3D.lookAt(lightPos, camTarget, up);
        float[] lightProj = Math3D.ortho(-80,80,-80,80, 1, 300);
        float[] lightVP = Math3D.mul(lightProj, lightView);

        // pass 1: 阴影（低质量软件渲染时跳过物体绘制，仅清深度为 1 = 无阴影）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo);
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        float[] I = Math3D.identity();
        if (lowQuality) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        } else {
        GLES30.glUseProgram(shadowProg);
        int uLightVP = GLES30.glGetUniformLocation(shadowProg, "uLightVP");
        int uModelS = GLES30.glGetUniformLocation(shadowProg, "uModel");
        GLES30.glUniformMatrix4fv(uLightVP, 1, false, lightVP, 0);
        drawShadow(groundMesh, I, uModelS);
        drawShadow(roadMesh, I, uModelS);
        drawShadow(trunkMesh, I, uModelS);
        // 真实树木/道具投影
        for (int i = 0; i < sceneryCount; i++) {
            float x = scenery[i*6], y = scenery[i*6+1], z = scenery[i*6+2];
            // 距离剔除：只投影车附近的树，SwiftShader 下大幅降低 draw call
            float dx = x - camTarget[0], dz = z - camTarget[2];
            if (dx*dx + dz*dz > 90f*90f) continue;
            float s = scenery[i*6+3], yaw = scenery[i*6+4];
            int mi = (int) scenery[i*6+5];
            FrmModel mdl = modelFor(mi);
            if (mdl == null) continue;
            float[] model = Math3D.trs(x, y, z, yaw, s, s, s);
            for (FrmModel.Node node : mdl.nodes) {
                float[] nm = Math3D.mul(model, node.matrix);
                GLES30.glUniformMatrix4fv(uModelS, 1, false, nm, 0);
                for (FrmModel.Prim prim : node.prims) prim.mesh.draw();
            }
        }
        for (Car c : state.cars) {
            if (!c.player && (state.phase == GameState.Phase.ROAM
                    || state.phase == GameState.Phase.MENU)) continue;
            // 远处对手车不投影
            if (!c.player) {
                float dx = c.x - p.x, dz = c.z - p.z;
                if (dx*dx + dz*dz > 70f*70f) continue;
            }
            float[] body = carBodyMatrix(c);
            for (FrmModel.Node node : c.model.nodes) {
                float[] nm = Math3D.mul(body, node.matrix);
                GLES30.glUniformMatrix4fv(uModelS, 1, false, nm, 0);
                for (FrmModel.Prim prim : node.prims) prim.mesh.draw();
            }
        }
        } // end else (shadow pass)

        // pass 2: 主渲染到 HDR FBO
        if (postFX == null) {
            postFX = new PostFX();
            int rw = lowQuality ? Math.max(2, viewW/2) : Math.max(2, viewW);
            int rh = lowQuality ? Math.max(2, viewH/2) : Math.max(2, viewH);
            postFX.init(rw, rh);
        }
        postFX.bindHdr();
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glUseProgram(mainProg);
        int uVP = GLES30.glGetUniformLocation(mainProg, "uVP");
        int uModel = GLES30.glGetUniformLocation(mainProg, "uModel");
        int uLightVPM = GLES30.glGetUniformLocation(mainProg, "uLightVP");
        int uCam = GLES30.glGetUniformLocation(mainProg, "uCamPos");
        int uLightD = GLES30.glGetUniformLocation(mainProg, "uLightDir");
        int uTint = GLES30.glGetUniformLocation(mainProg, "uTint");
        GLES30.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        GLES30.glUniformMatrix4fv(uLightVPM, 1, false, lightVP, 0);
        GLES30.glUniform3fv(uCam, 1, camPos, 0);
        GLES30.glUniform3fv(uLightD, 1, lightDir, 0);

        // 阴影纹理绑定到单元 4
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mainProg, "uShadow"), 4);

        // 全景天空球（内表面）：关深度写，先画
        GLES30.glUseProgram(skyProg);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(skyProg, "uVP"), 1, false, vp, 0);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(skyProg, "uModel"), 1, false, I, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skyTex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(skyProg, "uSky"), 0);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        skyMesh.draw();
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glDepthMask(true);

        GLES30.glUseProgram(mainProg);
        GLES30.glUniform3f(uTint, 0.62f, 0.95f, 0.55f);   // 草地染绿
        drawLit(groundMesh, I, texGrass, texGrassN, texGrassR, 0f, uModel, uTint);
        drawLit(shoulderMesh, I, texGrass, texGrassN, texGrassR, 1f, uModel, uTint);
        GLES30.glUniform3f(uTint, 1f, 1f, 1f);
        drawLit(roadMesh, I, texRoad, texRoadN, texRoadR, 1f, uModel, uTint);
        drawLit(lineMesh, I, texLine, texLine, texLine, 1f, uModel, uTint);
        drawLit(trunkMesh, I, texBark, texBarkN, texBarkR, 1f, uModel, uTint);

        // 真实 CC0 树木/道具：用 PBR 着色器按材质绘制
        GLES30.glUseProgram(carProg);
        int tVP = GLES30.glGetUniformLocation(carProg, "uVP");
        int tModel = GLES30.glGetUniformLocation(carProg, "uModel");
        int tLightVP = GLES30.glGetUniformLocation(carProg, "uLightVP");
        int tCam = GLES30.glGetUniformLocation(carProg, "uCamPos");
        int tLightD = GLES30.glGetUniformLocation(carProg, "uLightDir");
        int tBase = GLES30.glGetUniformLocation(carProg, "uBaseColor");
        int tMR = GLES30.glGetUniformLocation(carProg, "uMetalRough");
        int tHasTex = GLES30.glGetUniformLocation(carProg, "uHasTex");
        int tShadow = GLES30.glGetUniformLocation(carProg, "uShadow");
        GLES30.glUniformMatrix4fv(tVP, 1, false, vp, 0);
        GLES30.glUniformMatrix4fv(tLightVP, 1, false, lightVP, 0);
        GLES30.glUniform3fv(tCam, 1, camPos, 0);
        GLES30.glUniform3fv(tLightD, 1, lightDir, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glUniform1i(tShadow, 4);
        for (int i = 0; i < sceneryCount; i++) {
            float x = scenery[i*6], y = scenery[i*6+1], z = scenery[i*6+2];
            // 距离剔除：远处的树不画，靠雾化+远景树干网格填充
            float dx = x - camTarget[0], dz = z - camTarget[2];
            float maxD = lowQuality ? 60f : 120f;
            if (dx*dx + dz*dz > maxD*maxD) continue;
            float s = scenery[i*6+3], yaw = scenery[i*6+4];
            int mi = (int) scenery[i*6+5];
            FrmModel mdl = modelFor(mi);
            if (mdl == null) continue;
            float[] model = Math3D.trs(x, y, z, yaw, s, s, s);
            for (FrmModel.Node node : mdl.nodes) {
                float[] nm = Math3D.mul(model, node.matrix);
                GLES30.glUniformMatrix4fv(tModel, 1, false, nm, 0);
                for (FrmModel.Prim prim : node.prims) {
                    FrmModel.Material mat = prim.mat;
                    // 树木/道具：有贴图则采样贴图（保留枝叶细节），基色做色调
                    boolean useTex = mdl.textures != null && !mdl.textures.isEmpty();
                    if (mi <= 2) GLES30.glUniform4f(tBase, 0.55f, 0.85f, 0.50f, 1f);      // 松树
                    else if (mi == 3) GLES30.glUniform4f(tBase, 0.60f, 0.90f, 0.55f, 1f);  // 灌木
                    else if (mi == 4) GLES30.glUniform4f(tBase, 0.75f, 0.75f, 0.78f, 1f); // 岩石
                    else GLES30.glUniform4f(tBase, 0.75f, 0.76f, 0.78f, 1f);              // 护栏/路灯
                    GLES30.glUniform2f(tMR, 0.0f, 0.85f);
                    GLES30.glUniform1i(tHasTex, useTex ? 1 : 0);
                    if (useTex) {
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mdl.textures.get(0));
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(carProg, "uDiffuse"), 0);
                    }
                    prim.mesh.draw();
                }
            }
        }
        // 恢复主程序给后续（车部分会重新绑定 carProg）
        GLES30.glUseProgram(mainProg);

        // 高模跑车：PBR 着色器，按节点 + 材质绘制
        GLES30.glUseProgram(carProg);
        int cVP = GLES30.glGetUniformLocation(carProg, "uVP");
        int cModel = GLES30.glGetUniformLocation(carProg, "uModel");
        int cLightVP = GLES30.glGetUniformLocation(carProg, "uLightVP");
        int cCam = GLES30.glGetUniformLocation(carProg, "uCamPos");
        int cLightD = GLES30.glGetUniformLocation(carProg, "uLightDir");
        int cBase = GLES30.glGetUniformLocation(carProg, "uBaseColor");
        int cMR = GLES30.glGetUniformLocation(carProg, "uMetalRough");
        int cHasTex = GLES30.glGetUniformLocation(carProg, "uHasTex");
        int cShadow = GLES30.glGetUniformLocation(carProg, "uShadow");
        GLES30.glUniformMatrix4fv(cVP, 1, false, vp, 0);
        GLES30.glUniformMatrix4fv(cLightVP, 1, false, lightVP, 0);
        GLES30.glUniform3fv(cCam, 1, camPos, 0);
        GLES30.glUniform3fv(cLightD, 1, lightDir, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glUniform1i(cShadow, 4);

        int[] carColors = {0xE8C820, 0x1B7A4B, 0x8C2028, 0x2058A8, 0xD8D8DC, 0x6A3A9A};
        for (Car c : state.cars) {
            if (!c.player && (state.phase == GameState.Phase.ROAM
                    || state.phase == GameState.Phase.MENU)) continue;
            if (!c.player) {
                float dx = c.x - p.x, dz = c.z - p.z;
                if (dx*dx + dz*dz > 75f*75f) continue;
            }
            float[] body = carBodyMatrix(c);
            int ci = c.colorIndex;
            if (lowQuality) {
                // 软件渲染：用降面 LOD（保留真实车形与漆面，仅减少三角形）
                FrmModel lod = carModelsLod[ci % carModelsLod.length];
                for (FrmModel.Node node : lod.nodes) {
                    boolean isWheel = node.name.startsWith("Wheel");
                    float[] nm = Math3D.mul(body, node.matrix);
                    if (isWheel) nm = wheelMatrix(c, node, body);
                    GLES30.glUniformMatrix4fv(cModel, 1, false, nm, 0);
                    for (FrmModel.Prim prim : node.prims) {
                        GLES30.glUniform4fv(cBase, 1, toRGB(carColors[ci % carColors.length]), 0);
                        GLES30.glUniform2f(cMR, 0.35f, 0.26f);
                        GLES30.glUniform1i(cHasTex, 0);
                        prim.mesh.draw();
                    }
                }
                continue;
            }
            for (FrmModel.Node node : c.model.nodes) {
                boolean isWheel = node.name.startsWith("Wheel");
                float[] nm = Math3D.mul(body, node.matrix);
                if (isWheel) nm = wheelMatrix(c, node, body);
                GLES30.glUniformMatrix4fv(cModel, 1, false, nm, 0);
                for (FrmModel.Prim prim : node.prims) {
                    FrmModel.Material mat = prim.mat;
                    // 车漆用玩家/AI 区分色，其余用素材原色
                    boolean paint = mat.base != null && mat.base[3] > 0.5f;
                    float[] bc = paint ? toRGB(carColors[ci % carColors.length]) : mat.base;
                    GLES30.glUniform4fv(cBase, 1, bc, 0);
                    GLES30.glUniform2f(cMR, mat.metallic, mat.roughness);
                    boolean hasTex = mat.baseTex >= 0 && mat.hasUV;
                    GLES30.glUniform1i(cHasTex, hasTex ? 1 : 0);
                    if (hasTex) {
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, c.model.textures.get(mat.baseTex));
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(carProg, "uDiffuse"), 0);
                    }
                    if (mat.normalTex >= 0 && mat.hasUV) {
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, c.model.textures.get(mat.normalTex));
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(carProg, "uNormalTex"), 1);
                    }
                    prim.mesh.draw();
                }
            }
        }

        // 后处理：软件渲染降级为简化合成，真机全质量 Bloom + ACES
        postFX.composite(lowQuality, viewW, viewH);

        hud.onHud(state.hudText(), state.countdownText(),
                state.phase == GameState.Phase.RACE || state.phase == GameState.Phase.COUNTDOWN);

        // 帧率诊断（每秒输出一次，供实测定位性能瓶颈）
        frameCount++;
        long tnow = System.currentTimeMillis();
        if (tnow - frameTimeStart >= 1000) {
            android.util.Log.d("FRfps", "fps=" + frameCount + " phase=" + state.phase
                    + " scenery=" + sceneryCount + " cars=" + state.cars.length);
            frameCount = 0;
            frameTimeStart = tnow;
        }
    }
    private int frameCount = 0;
    private long frameTimeStart = System.currentTimeMillis();

    /** 车身世界矩阵：位置、朝向、侧倾。glTF 车模 +Z 朝前，Y 向上。 */
    private float[] carBodyMatrix(Car c) {
        float[] m = Math3D.trs(c.x, c.y, c.z, c.yaw, 1,1,1);
        if (Math.abs(c.bodyRoll) > 0.0001f) {
            float cr = (float)Math.cos(c.bodyRoll), sr = (float)Math.sin(c.bodyRoll);
            // 绕 Z 轴（车体前向）侧倾，叠加到旋转部分
            float[] roll = {
                cr, sr, 0, 0,
                -sr, cr, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
            };
            m = Math3D.mul(m, roll);
        }
        return m;
    }

    /** 车轮矩阵：在车身基础上绕轮轴滚动 + 前轮转向。 */
    private float[] wheelMatrix(Car c, FrmModel.Node node, float[] body) {
        float[] nm = Math3D.mul(body, node.matrix);
        // 车轮绕自身 X 轴滚动（glTF 车轮已定位），前轮额外转向
        float cr = (float)Math.cos(c.wheelRoll), sr = (float)Math.sin(c.wheelRoll);
        float[] spin = {
            1, 0, 0, 0,
            0, cr, -sr, 0,
            0, sr, cr, 0,
            0, 0, 0, 1
        };
        nm = Math3D.mul(nm, spin);
        if (node.name.equals("Wheel_FL") || node.name.equals("Wheel_FR")) {
            float st = c.steer * 0.5f;
            float cs = (float)Math.cos(st), ss = (float)Math.sin(st);
            float[] steerM = {
                cs, 0, ss, 0,
                0, 1, 0, 0,
                -ss, 0, cs, 0,
                0, 0, 0, 1
            };
            nm = Math3D.mul(nm, steerM);
        }
        return nm;
    }

    private FrmModel modelFor(int idx) {
        if (idx >= 0 && idx <= 4) return natureModels[idx];
        if (idx == 5) return propModels[0];   // 护栏
        if (idx == 6) return propModels[1];   // 路灯
        return null;
    }

    private static float[] toRGB(int argb) {
        return new float[]{((argb>>16)&255)/255f, ((argb>>8)&255)/255f, (argb&255)/255f, 1f};
    }

    private void tint(int idx, int loc) {
        int[] cs = {0xD8DCE0, 0xC0392B, 0x1F6FB2, 0xE1B12C, 0x27AE60, 0x8E44AD};
        int c = cs[Math.max(0, Math.min(5, idx))];
        GLES30.glUniform3f(loc, ((c>>16)&255)/255f, ((c>>8)&255)/255f, (c&255)/255f);
    }

    private void drawShadow(GLUtil.Mesh mesh, float[] model, int loc) {
        GLES30.glUniformMatrix4fv(loc, 1, false, model, 0);
        mesh.draw();
    }

    private void drawLit(GLUtil.Mesh mesh, float[] model, int diff, int norm, int rough,
                         float shine, int uModel, int uTint) {
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0);
        bind(mainProg, "uDiffuse", 0, diff);
        bind(mainProg, "uNormal", 1, norm);
        bind(mainProg, "uRough", 2, rough);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mainProg, "uShine"), shine);
        mesh.draw();
    }

    private void bind(int prog, String name, int unit, int tex) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit);
    }

    @Override
    public Car create(Track track, boolean player, int aiIndex, int colorIndex) {
        FrmModel mdl = carModels[colorIndex % carModels.length];
        return new Car(track, player, aiIndex, colorIndex, mdl);
    }

    private static final String SKY_VS =
        "#version 300 es\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "uniform mat4 uVP;\nuniform mat4 uModel;\n" +
        "out vec3 vDir;\n" +
        "void main(){\n" +
        "  vDir = normalize(aPos);\n" +
        "  gl_Position = uVP * uModel * vec4(aPos, 1.0);\n" +
        "}\n";

    private static final String SKY_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec3 vDir;\nuniform sampler2D uSky;\nout vec4 frag;\n" +
        "void main(){\n" +
        "  float h = clamp(vDir.y * 0.5 + 0.5, 0.0, 1.0);\n" +
        "  vec3 horizon = vec3(0.72, 0.82, 0.90);\n" +
        "  vec3 zenith = vec3(0.30, 0.52, 0.82);\n" +
        "  vec3 ground = vec3(0.55, 0.60, 0.58);\n" +
        "  vec3 c = h > 0.5 ? mix(horizon, zenith, (h-0.5)*2.0)\n" +
        "                   : mix(ground, horizon, h*2.0);\n" +
        "  // 柔和太阳光晕\n" +
        "  vec3 sunDir = normalize(vec3(-0.45, 0.82, -0.35));\n" +
        "  float s = max(dot(normalize(vDir), sunDir), 0.0);\n" +
        "  c += vec3(1.0, 0.92, 0.75) * pow(s, 64.0) * 0.8;\n" +
        "  c += vec3(1.0, 0.95, 0.85) * pow(s, 8.0) * 0.12;\n" +
        "  frag = vec4(c, 1.0);\n" +
        "}\n";

    private static final String SHADOW_VS =
        "#version 300 es\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "uniform mat4 uLightVP;\nuniform mat4 uModel;\n" +
        "void main(){ gl_Position = uLightVP * uModel * vec4(aPos,1.0); }\n";

    private static final String SHADOW_FS =
        "#version 300 es\nprecision mediump float;\n" +
        "void main(){}\n";

    private static final String MAIN_VS =
        "#version 300 es\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec3 aNormal;\n" +
        "layout(location=2) in vec2 aUV;\n" +
        "uniform mat4 uVP;\nuniform mat4 uModel;\nuniform mat4 uLightVP;\n" +
        "out vec3 vWorld;\nout vec3 vNormal;\nout vec2 vUV;\nout vec4 vShadow;\n" +
        "void main(){\n" +
        "  vec4 w = uModel * vec4(aPos,1.0);\n" +
        "  vWorld = w.xyz;\n" +
        "  vNormal = mat3(uModel) * aNormal;\n" +
        "  vUV = aUV;\n" +
        "  vec4 s = uLightVP * w;\n" +
        "  vShadow = vec4(s.xy*0.5+0.5, s.z*0.5+0.5, 1.0);\n" +
        "  gl_Position = uVP * w;\n" +
        "}\n";

    private static final String MAIN_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec3 vWorld;\nin vec3 vNormal;\nin vec2 vUV;\nin vec4 vShadow;\n" +
        "uniform sampler2D uDiffuse;\nuniform sampler2D uNormal;\nuniform sampler2D uRough;\n" +
        "uniform sampler2DShadow uShadow;\n" +
        "uniform vec3 uCamPos;\nuniform vec3 uLightDir;\nuniform vec3 uTint;\n" +
        "uniform float uShine;\n" +
        "out vec4 frag;\n" +
        "float shadowFactor(){\n" +
        "  vec3 c = vShadow.xyz;\n" +
        "  if(c.x<0.0||c.x>1.0||c.y<0.0||c.y>1.0||c.z>1.0) return 1.0;\n" +
        "  c.z -= 0.0014;\n" +
        "  float s = 0.0;\n" +
        "  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++){\n" +
        "    s += texture(uShadow, vec3(c.xy + vec2(float(x),float(y))/2048.0, c.z));\n" +
        "  }\n" +
        "  return s/9.0;\n" +
        "}\n" +
        "void main(){\n" +
        "  vec3 srgb = texture(uDiffuse, vUV).rgb;\n" +
        "  vec3 base = pow(srgb, vec3(2.2)) * uTint;\n" +  // sRGB -> 线性空间
        "  vec3 nm = texture(uNormal, vUV).rgb * 2.0 - 1.0;\n" +
        "  float rough = texture(uRough, vUV).r;\n" +
        "  vec3 geoN = normalize(vNormal);\n" +
        "  if(!gl_FrontFacing) geoN = -geoN;\n" +
        "  vec3 N = normalize(geoN + vec3(nm.xy*0.25, 0.0));\n" +
        "  vec3 L = normalize(-uLightDir);\n" +
        "  vec3 V = normalize(uCamPos - vWorld);\n" +
        "  vec3 H = normalize(L + V);\n" +
        "  float diff = max(dot(N,L),0.0);\n" +
        "  float spec = pow(max(dot(N,H),0.0), mix(90.0, 14.0, rough)) * uShine;\n" +
        "  float sh = shadowFactor();\n" +
        "  vec3 skyCol = vec3(0.52,0.62,0.72);\n" +
        "  vec3 groundCol = vec3(0.24,0.28,0.18);\n" +
        "  float hemi = 0.5 + 0.5*N.y;\n" +
        "  vec3 ambient = mix(groundCol, skyCol, hemi) * 0.70;\n" +
        "  vec3 sunCol = vec3(1.05,0.98,0.82);\n" +
        "  vec3 color = base * ambient + base * diff * sh * sunCol * 0.95\n" +
        "               + vec3(spec) * sh * 0.45;\n" +
        "  float d = length(uCamPos - vWorld);\n" +
        "  float fog = 1.0 - exp(-pow(d*0.0018, 2.0));\n" +
        "  color = mix(color, vec3(0.62,0.72,0.80), clamp(fog,0.0,0.25));\n" +
        "  frag = vec4(color,1.0);\n" +
        "}\n";

    private static final String CAR_VS =
        "#version 300 es\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec3 aNormal;\n" +
        "layout(location=2) in vec2 aUV;\n" +
        "uniform mat4 uVP;\nuniform mat4 uModel;\nuniform mat4 uLightVP;\n" +
        "out vec3 vWorld;\nout vec3 vNormal;\nout vec2 vUV;\nout vec4 vShadow;\n" +
        "void main(){\n" +
        "  vec4 w = uModel * vec4(aPos,1.0);\n" +
        "  vWorld = w.xyz;\n" +
        "  vNormal = normalize(mat3(uModel) * aNormal);\n" +
        "  vUV = aUV;\n" +
        "  vec4 s = uLightVP * w;\n" +
        "  vShadow = vec4(s.xy*0.5+0.5, s.z*0.5+0.5, 1.0);\n" +
        "  gl_Position = uVP * w;\n" +
        "}\n";

    private static final String CAR_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec3 vWorld;\nin vec3 vNormal;\nin vec2 vUV;\nin vec4 vShadow;\n" +
        "uniform sampler2D uDiffuse;\nuniform sampler2D uNormalTex;\nuniform sampler2DShadow uShadow;\n" +
        "uniform vec3 uCamPos;\nuniform vec3 uLightDir;\nuniform vec4 uBaseColor;\n" +
        "uniform vec2 uMetalRough;\nuniform int uHasTex;\nout vec4 frag;\n" +
        "float shadowFactor(){\n" +
        "  vec3 c = vShadow.xyz;\n" +
        "  if(c.x<0.0||c.x>1.0||c.y<0.0||c.y>1.0||c.z>1.0) return 1.0;\n" +
        "  c.z -= 0.0009;\n" +
        "  float s=0.0;\n" +
        "  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++)\n" +
        "    s += texture(uShadow, vec3(c.xy+vec2(float(x),float(y))/2048.0, c.z));\n" +
        "  return s/9.0;\n" +
        "}\n" +
        "void main(){\n" +
        "  vec3 albedo = pow(uBaseColor.rgb, vec3(2.2));\n" +
        "  if(uHasTex==1) albedo *= pow(texture(uDiffuse, vUV).rgb, vec3(2.2));\n" +
        "  float metal = uMetalRough.x;\n" +
        "  float rough = max(uMetalRough.y, 0.04);\n" +
        "  vec3 N = normalize(vNormal);\n" +
        "  if(!gl_FrontFacing) N = -N;\n" +
        "  vec3 L = normalize(-uLightDir);\n" +
        "  vec3 V = normalize(uCamPos - vWorld);\n" +
        "  vec3 H = normalize(L+V);\n" +
        "  float NdotL = max(dot(N,L), 0.0);\n" +
        "  float NdotH = max(dot(N,H), 0.0);\n" +
        "  float NdotV = max(dot(N,V), 0.001);\n" +
        "  // Cook-Torrance 近似\n" +
        "  float a = rough*rough;\n" +
        "  float a2 = a*a;\n" +
        "  float d = NdotH*NdotH*(a2-1.0)+1.0;\n" +
        "  float D = a2/(3.14159*d*d);\n" +
        "  float k = (rough+1.0)*(rough+1.0)/8.0;\n" +
        "  float G = (NdotV/(NdotV*(1.0-k)+k))*(NdotL/(NdotL*(1.0-k)+k));\n" +
        "  vec3 F0 = mix(vec3(0.04), albedo, metal);\n" +
        "  vec3 F = F0 + (1.0-F0)*pow(1.0-NdotH, 5.0);\n" +
        "  vec3 spec = (D*G*F)/(4.0*NdotV*NdotL+0.001);\n" +
        "  vec3 kd = (1.0-F)*(1.0-metal);\n" +
        "  float sh = shadowFactor();\n" +
        "  vec3 skyCol = vec3(0.55,0.68,0.85);\n" +
        "  vec3 amb = mix(vec3(0.22,0.24,0.20), skyCol, 0.5+0.5*N.y);\n" +
        "  vec3 direct = (kd*albedo/3.14159 + spec) * NdotL * sh * vec3(1.25,1.18,1.02) * 3.0;\n" +
        "  vec3 color = direct + albedo*amb*(1.0-metal*0.5) + F0*amb*metal;\n" +
        "  // 车漆清漆高光\n" +
        "  float clear = pow(NdotH, 128.0) * (1.0-rough) * 0.6;\n" +
        "  color += vec3(clear) * sh;\n" +
        "  float dist = length(uCamPos - vWorld);\n" +
        "  float fog = 1.0 - exp(-pow(dist*0.0024, 2.0));\n" +
        "  color = mix(color, vec3(0.62,0.75,0.88), clamp(fog,0.0,0.5));\n" +
        "  frag = vec4(color, 1.0);\n" +
        "}\n";
}
