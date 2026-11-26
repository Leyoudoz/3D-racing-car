package com.forest.racer;

import android.opengl.GLES30;

/** 电影级后处理：HDR 场景 FBO -> 亮度提取 -> 高斯模糊 Bloom -> ACES 色调映射合成。 */
final class PostFX {
    private int hdrFbo, hdrColor, hdrDepth;
    private int pingFboA, pingTexA, pingFboB, pingTexB;
    private int progBright, progBlur, progComposite, progDepthPre;
    private int w, h;
    private static final int BLOOM_DIV = 4;
    boolean hdrSupported = false;

    void init(int width, int height) {
        this.w = width; this.h = height;
        progBright = GLUtil.program(QUAD_VS, BRIGHT_FS);
        progBlur = GLUtil.program(QUAD_VS, BLUR_FS);
        progComposite = GLUtil.program(QUAD_VS, COMPOSITE_FS);

        // 尝试 RGBA16F，用 FBO 完整性检测；不支持则回退 RGBA8
        String exts = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        hdrSupported = exts != null && exts.contains("GL_EXT_color_buffer_float");
        hdrColor = createTex(width, height,
                hdrSupported ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8,
                GLES30.GL_RGBA,
                hdrSupported ? GLES30.GL_HALF_FLOAT : GLES30.GL_UNSIGNED_BYTE,
                GLES30.GL_LINEAR);

        hdrDepth = createDepthTex(width, height);
        int[] f = new int[1];
        GLES30.glGenFramebuffers(1, f, 0); hdrFbo = f[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, hdrColor, 0);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D, hdrDepth, 0);

        int bw = Math.max(1, width/BLOOM_DIV), bh = Math.max(1, height/BLOOM_DIV);
        pingTexA = createTex(bw, bh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
        pingTexB = createTex(bw, bh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
        GLES30.glGenFramebuffers(1, f, 0); pingFboA = f[0];
        GLES30.glGenFramebuffers(1, f, 0); pingFboB = f[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFboA);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, pingTexA, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFboB);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, pingTexB, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }




    private int createTex(int w, int h, int internal, int fmt, int type, int filter) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, fmt, type, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private int createDepthTex(int w, int h) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24,
                w, h, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        return t[0];
    }

    void bindHdr() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFbo);
        GLES30.glViewport(0, 0, w, h);
    }

    /** 后处理合成到默认帧缓冲。lowQuality 时走简化路径（仅 ACES+伽马，跳过 Bloom 多次全屏 pass）。 */
    void composite(boolean lowQuality, int outW, int outH) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);

        if (lowQuality) {
            // 简化合成：单次全屏 pass，ACES+伽马，跳过亮度提取和两轮模糊
            GLES30.glUseProgram(progComposite);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrColor);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTexA);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progComposite, "uScene"), 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progComposite, "uBloom"), 1);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progComposite, "uBloomStrength"), 0f);
            drawQuad();
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            return;
        }

        // 1. 亮度提取 -> pingA
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFboA);
        GLES30.glViewport(0, 0, w/BLOOM_DIV, h/BLOOM_DIV);
        GLES30.glUseProgram(progBright);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrColor);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progBright, "uTex"), 0);
        drawQuad();

        // 2. 两次高斯模糊 pingA->pingB->pingA
        GLES30.glUseProgram(progBlur);
        int locDir = GLES30.glGetUniformLocation(progBlur, "uDir");
        for (int pass = 0; pass < 2; pass++) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFboB);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTexA);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progBlur, "uTex"), 0);
            GLES30.glUniform2f(locDir, 1f/(w/BLOOM_DIV), 0f);
            drawQuad();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFboA);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTexB);
            GLES30.glUniform2f(locDir, 0f, 1f/(h/BLOOM_DIV));
            drawQuad();
        }

        // 3. ACES 合成
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(progComposite);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrColor);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTexA);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progComposite, "uScene"), 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progComposite, "uBloom"), 1);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(progComposite, "uBloomStrength"), 0.55f);
        drawQuad();
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    private static int quadVao = 0;
    private static void drawQuad() {
        if (quadVao == 0) {
            int[] v = new int[1];
            GLES30.glGenVertexArrays(1, v, 0);
            quadVao = v[0];
            GLES30.glBindVertexArray(quadVao);
        } else {
            GLES30.glBindVertexArray(quadVao);
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private static final String QUAD_VS =
        "#version 300 es\n" +
        "out vec2 vUV;\n" +
        "void main(){\n" +
        "  vec2 p = vec2((gl_VertexID<<1)&2, gl_VertexID&2);\n" +
        "  vUV = p;\n" +
        "  gl_Position = vec4(p*2.0-1.0, 0.0, 1.0);\n" +
        "}\n";

    private static final String BRIGHT_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec2 vUV;\nuniform sampler2D uTex;\nout vec4 frag;\n" +
        "void main(){\n" +
        "  vec3 c = texture(uTex, vUV).rgb;\n" +
        "  float l = dot(c, vec3(0.2126,0.7152,0.0722));\n" +
        "  frag = vec4(c * smoothstep(0.7, 1.2, l), 1.0);\n" +
        "}\n";

    private static final String BLUR_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec2 vUV;\nuniform sampler2D uTex;\nuniform vec2 uDir;\nout vec4 frag;\n" +
        "void main(){\n" +
        "  vec3 s = texture(uTex, vUV).rgb * 0.227;\n" +
        "  s += texture(uTex, vUV + uDir*1.384).rgb * 0.316;\n" +
        "  s += texture(uTex, vUV - uDir*1.384).rgb * 0.316;\n" +
        "  s += texture(uTex, vUV + uDir*3.231).rgb * 0.070;\n" +
        "  s += texture(uTex, vUV - uDir*3.231).rgb * 0.070;\n" +
        "  frag = vec4(s, 1.0);\n" +
        "}\n";

    private static final String COMPOSITE_FS =
        "#version 300 es\nprecision highp float;\n" +
        "in vec2 vUV;\nuniform sampler2D uScene;\nuniform sampler2D uBloom;\nuniform float uBloomStrength;\nout vec4 frag;\n" +
        "vec3 aces(vec3 x){\n" +
        "  const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;\n" +
        "  return clamp((x*(a*x+b))/(x*(c*x+d)+e), 0.0, 1.0);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec3 scene = texture(uScene, vUV).rgb;\n" +
        "  vec3 bloom = texture(uBloom, vUV).rgb;\n" +
        "  vec3 c = scene + bloom * uBloomStrength;\n" +
        "  c = aces(c * 1.25);\n" +
        "  float d = distance(vUV, vec2(0.5));\n" +
        "  c *= 1.0 - 0.20*d*d;\n" +  // 暗角
        "  c = pow(c, vec3(1.0/2.2));\n" +  // 线性 -> sRGB 伽马编码
        "  frag = vec4(c, 1.0);\n" +
        "}\n";
}
