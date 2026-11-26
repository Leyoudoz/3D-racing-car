package com.forest.racer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.opengl.GLSurfaceView;

import com.forest.racer.input.GamepadInput;

/** 入口：GLSurfaceView 渲染 + 可见虚拟按键 + 手柄输入 + 菜单/HUD。 */
public class MainActivity extends Activity implements GameRenderer.HudCallback {

    private GLSurfaceView glView;
    private GameRenderer renderer;
    private AudioManager audio;
    private GamepadInput gamepad;
    private TextView hud, center, menuTitle;
    private LinearLayout menuPanel;
    private Button btnMusic;
    private TouchControls controls;
    private View loadingOverlay;
    private TextView loadText, loadPercent;
    private android.widget.ProgressBar loadBar;

    // 触控状态（由虚拟按键和手势共同驱动）
    volatile boolean touchLeft, touchRight, touchGas, touchBrake;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        audio = new AudioManager(this);
        audio.init();
        audio.startAmbient();
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        gamepad = im != null ? new GamepadInput(im) : null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(70, 96, 120));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        renderer = new GameRenderer(this, this, audio);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 可见虚拟按键层
        controls = new TouchControls(this);
        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        hud.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        hud.setPadding(dp(16), dp(14), dp(8), dp(8));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.START;
        root.addView(hud, hp);

        center = new TextView(this);
        center.setTextColor(Color.WHITE);
        center.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        center.setGravity(Gravity.CENTER);
        center.setShadowLayer(8f, 2f, 2f, Color.BLACK);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        root.addView(center, cp);

        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setGravity(Gravity.CENTER);
        menuPanel.setBackgroundColor(Color.argb(220, 10, 22, 18));
        menuPanel.setPadding(dp(28), dp(32), dp(28), dp(32));
        menuTitle = new TextView(this);
        menuTitle.setTextColor(Color.rgb(240, 246, 236));
        menuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        menuTitle.setGravity(Gravity.CENTER);
        menuTitle.setText("森林极速\n赛道 1/21  晨雾松林");
        menuPanel.addView(menuTitle);

        Button prev = mkButton("上一条赛道", v -> stepTrack(-1));
        Button next = mkButton("下一条赛道", v -> stepTrack(1));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(prev, linearLayoutParam());
        nav.addView(next, linearLayoutParam());
        menuPanel.addView(nav, rowParam());

        Button start = mkButton("开始比赛（3 圈 / 6 车）", v -> startRace());
        menuPanel.addView(start, rowParam());

        Button roam = mkButton("自由漫游（无对手）", v -> startRoam());
        menuPanel.addView(roam, rowParam());

        btnMusic = mkButton("轻音乐：开", v -> {
            boolean on = !audio.isMusicOn();
            audio.setMusicOn(on);
            btnMusic.setText(on ? "轻音乐：开" : "轻音乐：关");
        });
        menuPanel.addView(btnMusic, rowParam());

        TextView tips = new TextView(this);
        tips.setTextColor(Color.rgb(200, 214, 200));
        tips.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tips.setGravity(Gravity.CENTER);
        tips.setText("触摸按键或手柄操控\nA 开始 / B 返回 / X 音乐 / Y 赛道");
        menuPanel.addView(tips, rowParam());

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                dp(360), FrameLayout.LayoutParams.WRAP_CONTENT);
        mp.gravity = Gravity.CENTER;
        root.addView(menuPanel, mp);
        menuPanel.setVisibility(View.GONE);   // 加载完成才显示菜单

        // 加载页（全屏遮罩）
        loadingOverlay = new View(this) {
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.rgb(16, 30, 26));
                c.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout loadBox = new LinearLayout(this);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(
                dp(420), FrameLayout.LayoutParams.WRAP_CONTENT);
        lb.gravity = Gravity.CENTER;
        root.addView(loadBox, lb);

        TextView title = new TextView(this);
        title.setText("森林极速");
        title.setTextColor(Color.rgb(235, 245, 232));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setGravity(Gravity.CENTER);
        loadBox.addView(title);

        loadText = new TextView(this);
        loadText.setText("正在初始化");
        loadText.setTextColor(Color.rgb(180, 210, 185));
        loadText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        loadText.setGravity(Gravity.CENTER);
        loadBox.addView(loadText);

        loadBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadBar.setMax(100);
        loadBar.setProgress(0);
        LinearLayout.LayoutParams barP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        barP.topMargin = dp(20);
        loadBox.addView(loadBar, barP);

        loadPercent = new TextView(this);
        loadPercent.setText("0%");
        loadPercent.setTextColor(Color.rgb(150, 190, 160));
        loadPercent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        loadPercent.setGravity(Gravity.CENTER);
        loadBox.addView(loadPercent);

        loadingBox = loadBox;

        setContentView(root);
        audio.startMusic();
    }
    private View loadingBox;

    private Button mkButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.rgb(244, 248, 240));
        b.setBackgroundColor(Color.rgb(38, 82, 60));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams linearLayoutParam() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams rowParam() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void stepTrack(int d) {
        final int i = (renderer.state.selectedTrack + d + Track.COUNT) % Track.COUNT;
        // 赛道切换涉及 GL 网格重建，必须在 GL 线程执行，否则与 onDrawFrame 竞争导致空指针崩溃
        glView.queueEvent(() -> {
            renderer.state.selectTrack(i);
            renderer.rebuildTrack(renderer.state.track);
        });
        menuTitle.setText("森林极速\n赛道 " + (i+1) + "/21  " + Track.NAMES[i]);
        audio.playGear(1);
    }

    private void startRace() {
        menuPanel.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        controls.resetTouches();
        renderer.state.start();
        audio.playCountdown();
    }

    private void startRoam() {
        menuPanel.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        controls.resetTouches();
        renderer.state.startRoam();
    }

    private void backToMenu() {
        renderer.state.toMenu();
        menuPanel.setVisibility(View.VISIBLE);
        controls.setVisibility(View.GONE);
        controls.resetTouches();
    }

    @Override
    public void onBackPressed() {
        GameState.Phase p = renderer.state.phase;
        if (p == GameState.Phase.RACE || p == GameState.Phase.COUNTDOWN
                || p == GameState.Phase.RESULT || p == GameState.Phase.ROAM) {
            backToMenu();
        } else {
            super.onBackPressed();
        }
    }

    private boolean lastGamepadConnected = false;
    private long lastTouchTime = 0;

    /** 每帧聚合手柄 + 触摸输入。渲染线程会调用 renderer.setInput。 */
    private void pushInput() {
        float throttle = touchGas ? 1f : 0f;
        float brake = touchBrake ? 1f : 0f;
        float steer = (touchLeft ? -1f : 0f) + (touchRight ? 1f : 0f);
        boolean padConnected = gamepad != null && gamepad.isConnected();
        // 手柄连接且近期无触摸时隐藏虚拟按键；一旦触摸屏幕立即恢复显示
        if (padConnected != lastGamepadConnected) {
            lastGamepadConnected = padConnected;
            final boolean hide = padConnected;
            runOnUiThread(() -> {
                if (renderer.state.phase != GameState.Phase.MENU) {
                    controls.setVisibility(hide ? View.GONE : View.VISIBLE);
                }
            });
        }
        if (padConnected) {
            throttle = Math.max(throttle, gamepad.getThrottle());
            brake = Math.max(brake, gamepad.getBrake());
            float gs = gamepad.getSteer();
            if (Math.abs(gs) > 0.08f) steer = gs;
            GamepadInput.Event[] events = gamepad.consumeEvents();
            for (GamepadInput.Event e : events) {
                switch (e) {
                    case START:
                        if (renderer.state.phase == GameState.Phase.MENU) startRace();
                        break;
                    case BACK:
                        if (renderer.state.phase != GameState.Phase.MENU) backToMenu();
                        break;
                    case MUSIC:
                        boolean on = !audio.isMusicOn();
                        audio.setMusicOn(on);
                        runOnUiThread(() -> btnMusic.setText(on ? "轻音乐：开" : "轻音乐：关"));
                        break;
                    case TRACK:
                        stepTrack(1);
                        break;
                }
            }
        }
        renderer.setInput(throttle, brake, steer);
    }

    Runnable inputTick = new Runnable() {
        @Override public void run() {
            pushInput();
            glView.postDelayed(this, 16);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        audio.startMusic();
        glView.post(inputTick);
        if (gamepad != null) gamepad.refreshDevices();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.removeCallbacks(inputTick);
        glView.onPause();
        audio.pauseMusic();
    }

    @Override
    protected void onDestroy() {
        if (gamepad != null) gamepad.release();
        audio.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (gamepad != null && gamepad.dispatchKeyEvent(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (gamepad != null && gamepad.dispatchGenericMotionEvent(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchTime = System.currentTimeMillis();
            // 手柄模式下触摸屏幕，恢复虚拟按键显示
            if (controls.getVisibility() != View.VISIBLE
                    && renderer.state.phase != GameState.Phase.MENU) {
                controls.setVisibility(View.VISIBLE);
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void onHud(String text, String centerText, boolean racing) {
        runOnUiThread(() -> {
            hud.setText(text);
            hud.setVisibility(racing ? View.VISIBLE : View.GONE);
            center.setText(centerText);
        });
    }

    @Override
    public void onLoadProgress(int percent, String message) {
        runOnUiThread(() -> {
            if (loadBar != null) loadBar.setProgress(percent);
            if (loadPercent != null) loadPercent.setText(percent + "%");
            if (loadText != null) loadText.setText(message);
        });
    }

    @Override
    public void onLoaded() {
        runOnUiThread(() -> {
            // 保证加载页至少展示 1.2 秒，避免瞬间闪过
            loadingOverlay.postDelayed(() -> {
                if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                if (loadingBox != null) loadingBox.setVisibility(View.GONE);
                if (menuPanel != null) menuPanel.setVisibility(View.VISIBLE);
            }, 1200);
        });
    }

    /** 可见的半透明虚拟按键：左转向、右转向、油门、刹车，与触摸判定一致。 */
    private final class TouchControls extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RectF left, right, gas, brake;

        TouchControls(Context c) {
            super(c);
            setVisibility(View.GONE);
            setClickable(true);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            int pad = dp(24), size = dp(120);
            left = new RectF(pad, h - pad - size, pad + size, h - pad);
            right = new RectF(pad + size + dp(20), h - pad - size, pad + size*2 + dp(20), h - pad);
            gas = new RectF(w - pad - size, h - pad - size, w - pad, h - pad);
            brake = new RectF(w - pad - size*2 - dp(20), h - pad - size,
                    w - pad - size - dp(20), h - pad);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawBtn(c, left, touchLeft, "左", 0xFF2E7D5B);
            drawBtn(c, right, touchRight, "右", 0xFF2E7D5B);
            drawBtn(c, brake, touchBrake, "刹车", 0xFFB03A2E);
            drawBtn(c, gas, touchGas, "油门", 0xFF2E7D5B);
        }

        private void drawBtn(Canvas c, RectF r, boolean pressed, String label, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressed ? color : (color & 0x66FFFFFF));
            c.drawRoundRect(r, dp(24), dp(24), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.argb(180, 255, 255, 255));
            paint.setStrokeWidth(dp(2));
            c.drawRoundRect(r, dp(24), dp(24), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(30));
            c.drawText(label, r.centerX(), r.centerY() + dp(10), paint);
        }

        // 按 pointerId 跟踪每个手指当前按住的按键，避免松手后状态残留
        private final android.util.SparseIntArray pointerButton = new android.util.SparseIntArray();
        private static final int BTN_NONE = 0, BTN_LEFT = 1, BTN_RIGHT = 2, BTN_GAS = 3, BTN_BRAKE = 4;

        private int buttonAt(float x, float y) {
            if (left != null && left.contains(x, y)) return BTN_LEFT;
            if (right != null && right.contains(x, y)) return BTN_RIGHT;
            if (gas != null && gas.contains(x, y)) return BTN_GAS;
            if (brake != null && brake.contains(x, y)) return BTN_BRAKE;
            return BTN_NONE;
        }

        void resetTouches() {
            pointerButton.clear();
            touchLeft = touchRight = touchGas = touchBrake = false;
            invalidate();
        }

        private void recompute() {
            boolean l = false, rr = false, g = false, b = false;
            for (int i = 0; i < pointerButton.size(); i++) {
                int btn = pointerButton.valueAt(i);
                if (btn == BTN_LEFT) l = true;
                else if (btn == BTN_RIGHT) rr = true;
                else if (btn == BTN_GAS) g = true;
                else if (btn == BTN_BRAKE) b = true;
            }
            boolean changed = touchLeft != l || touchRight != rr || touchGas != g || touchBrake != b;
            touchLeft = l; touchRight = rr; touchGas = g; touchBrake = b;
            if (changed) invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            int idx = e.getActionIndex();
            int pid = e.getPointerId(idx);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                lastTouchTime = System.currentTimeMillis();
                pointerButton.put(pid, buttonAt(e.getX(idx), e.getY(idx)));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                // 手指抬起：删除该 pointerId 的记录，不用坐标反查（坐标反查会把抬起误判为仍按下）
                pointerButton.delete(pid);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                pointerButton.clear();
            } else if (action == MotionEvent.ACTION_MOVE) {
                // 手指在按键间滑动时更新其归属
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int p = e.getPointerId(i);
                    if (pointerButton.indexOfKey(p) >= 0) {
                        pointerButton.put(p, buttonAt(e.getX(i), e.getY(i)));
                    }
                }
            }
            recompute();
            return true;
        }
    }
}
