package com.forest.racer.input;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Android 手柄输入：通过 InputDevice 的按键/通用运动事件读取油门、刹车、转向和功能键。
 *
 * <p>使用方式：在 Activity 的 onKeyDown/onKeyUp/dispatchGenericMotionEvent/onInputDeviceChanged
 * 中调用本类对应的分发方法；游戏循环调用 getThrottle/getBrake/getSteer 取值，
 * 每帧结束调用 consumeEvents 取走一次性按键事件。
 *
 * <p>本类所有公开方法线程安全。
 */
public final class GamepadInput implements InputManager.InputDeviceListener {

    /** 一次性功能事件类型。 */
    public enum Event {
        START,
        BACK,
        MUSIC,
        TRACK
    }

    private static final float DEAD_ZONE = 0.12f;
    private static final long TRIGGER_EDGE_MS = 180L;

    private final InputManager inputManager;
    private final Object lock = new Object();

    /** 当前识别到的手柄设备 id，-1 表示无手柄。 */
    private int deviceId = -1;
    private boolean connected;

    private float throttle;
    private float brake;
    private float steer;

    private final Set<Event> pending = new HashSet<Event>(4);
    private final Map<Integer, Long> keyDownAt = new HashMap<Integer, Long>(8);

    /**
     * @param inputManager 系统 InputManager，可由 Context.getSystemService 获取；允许为 null
     */
    public GamepadInput(InputManager inputManager) {
        this.inputManager = inputManager;
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
            synchronized (lock) {
                scanDevicesLocked();
            }
        }
    }

    /** 注销设备监听，Activity 销毁时调用。 */
    public void release() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }
        synchronized (lock) {
            connected = false;
            deviceId = -1;
            throttle = 0f;
            brake = 0f;
            steer = 0f;
            keyDownAt.clear();
            pending.clear();
        }
    }

    /** 当前是否有手柄连接。 */
    public boolean isConnected() {
        synchronized (lock) {
            return connected;
        }
    }

    /** 当前手柄设备 id，无手柄返回 -1。 */
    public int getDeviceId() {
        synchronized (lock) {
            return connected ? deviceId : -1;
        }
    }

    /** 油门，归一化 0.0 至 1.0。 */
    public float getThrottle() {
        synchronized (lock) {
            return throttle;
        }
    }

    /** 刹车，归一化 0.0 至 1.0。 */
    public float getBrake() {
        synchronized (lock) {
            return brake;
        }
    }

    /** 转向，归一化 -1.0 至 1.0；负为左，正为右。 */
    public float getSteer() {
        synchronized (lock) {
            return steer;
        }
    }

    /** A 键是否处于按下状态（持续态），用于开始或确认。 */
    public boolean isStartPressed() {
        synchronized (lock) {
            return keyDownAt.containsKey(KeyEvent.KEYCODE_BUTTON_A);
        }
    }

    /**
     * 取走并清空自上次调用以来累积的一次性功能事件。
     *
     * @return 事件数组，可能为空数组；调用后内部队列清空
     */
    public Event[] consumeEvents() {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return new Event[0];
            }
            Event[] out = pending.toArray(new Event[pending.size()]);
            pending.clear();
            return out;
        }
    }

    /**
     * 分发按键事件。仅在事件来自手柄时处理。
     *
     * @return true 表示事件已被本类消费
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null || !isGamepad(event.getDevice())) {
            return false;
        }
        int code = event.getKeyCode();
        int action = event.getAction();
        long now = event.getEventTime();
        if (now <= 0L) {
            now = SystemClock.uptimeMillis();
        }
        synchronized (lock) {
            rememberDeviceLocked(event.getDeviceId());
            boolean down = action == KeyEvent.ACTION_DOWN;
            if (down) {
                Long previous = keyDownAt.get(code);
                // 系统自动重复不产生新事件。
                if (previous != null && event.getRepeatCount() > 0) {
                    return true;
                }
                if (previous == null || now - previous >= TRIGGER_EDGE_MS) {
                    keyDownAt.put(code, Long.valueOf(now));
                    handleButtonDownLocked(code);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                keyDownAt.remove(code);
            }
        }
        return true;
    }

    /**
     * 分发通用运动事件。仅在事件来自手柄时处理。
     *
     * @return true 表示事件已被本类消费
     */
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event == null || event.getAction() != MotionEvent.ACTION_MOVE
                || !isGamepad(event.getDevice())) {
            return false;
        }
        synchronized (lock) {
            rememberDeviceLocked(event.getDeviceId());
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            float axisX = event.getAxisValue(MotionEvent.AXIS_X);
            float axisY = event.getAxisValue(MotionEvent.AXIS_Y);
            float axisZ = event.getAxisValue(MotionEvent.AXIS_Z);
            float axisRz = event.getAxisValue(MotionEvent.AXIS_RZ);
            float axisBrake = event.getAxisValue(MotionEvent.AXIS_BRAKE);
            float axisThrottle = event.getAxisValue(MotionEvent.AXIS_THROTTLE);
            float axisLTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            float axisRTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            float axisHat = event.getAxisValue(MotionEvent.AXIS_HAT_X);

            float steerRaw = axisX;
            if (Math.abs(steerRaw) < Math.abs(axisZ)) {
                steerRaw = axisZ;
            }
            if (Math.abs(steerRaw) < Math.abs(axisHat)) {
                steerRaw = axisHat;
            }
            steer = applyDeadZone(steerRaw);

            // 多映射兼容：优先标准刹车/油门轴，其次扳机轴，再降级到 Y/RZ。
            float brakeRaw = pickPositive(axisBrake, axisLTrigger);
            float throttleRaw = pickPositive(axisThrottle, axisRTrigger);
            if (brakeRaw <= 0f && Math.abs(axisY) > 0f && axisY > 0f) {
                brakeRaw = axisY;
            }
            if (throttleRaw <= 0f && axisY < 0f) {
                throttleRaw = -axisY;
            }
            if (brakeRaw <= 0f && axisRz > 0f) {
                brakeRaw = axisRz;
            }
            if (device != null && hasAxis(device, MotionEvent.AXIS_GAS)) {
                float gas = event.getAxisValue(MotionEvent.AXIS_GAS);
                if (gas > throttleRaw) {
                    throttleRaw = gas;
                }
            }
            throttle = clamp01(applyDeadZone(throttleRaw));
            brake = clamp01(applyDeadZone(brakeRaw));

            // 部分手柄把扳机编码为 -1..1 的 Z/RZ，需取绝对值。
            boolean zIsTrigger = device != null && hasAxis(device, MotionEvent.AXIS_Z)
                    && !hasAxis(device, MotionEvent.AXIS_X);
            if (zIsTrigger) {
                brake = clamp01(Math.max(brake, applyDeadZone(Math.abs(axisZ))));
                throttle = clamp01(Math.max(throttle, applyDeadZone(Math.abs(axisRz))));
                steer = applyDeadZone(axisX);
            }
        }
        return true;
    }

    @Override
    public void onInputDeviceAdded(int addedId) {
        synchronized (lock) {
            if (!connected) {
                rememberDeviceLocked(addedId);
            }
        }
    }

    @Override
    public void onInputDeviceRemoved(int removedId) {
        synchronized (lock) {
            if (deviceId == removedId) {
                connected = false;
                deviceId = -1;
                throttle = 0f;
                brake = 0f;
                steer = 0f;
                keyDownAt.clear();
                scanDevicesLocked();
            }
        }
    }

    @Override
    public void onInputDeviceChanged(int changedId) {
        synchronized (lock) {
            if (!connected) {
                rememberDeviceLocked(changedId);
            }
        }
    }

    /** 由宿主在 Activity.onInputDeviceChanged 或焦点变化时主动调用，用于热插拔兜底。 */
    public void refreshDevices() {
        synchronized (lock) {
            scanDevicesLocked();
        }
    }

    private void handleButtonDownLocked(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
                pending.add(Event.START);
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                pending.add(Event.BACK);
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                pending.add(Event.MUSIC);
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                pending.add(Event.TRACK);
                break;
            default:
                break;
        }
    }

    private void rememberDeviceLocked(int id) {
        if (id < 0) {
            return;
        }
        if (!connected || deviceId != id) {
            connected = true;
            deviceId = id;
        }
    }

    private void scanDevicesLocked() {
        if (inputManager == null) {
            return;
        }
        int[] ids = inputManager.getInputDeviceIds();
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (isGamepad(dev)) {
                connected = true;
                deviceId = id;
                return;
            }
        }
        connected = false;
        deviceId = -1;
    }

    private static boolean hasAxis(InputDevice device, int axis) {
        return device != null && device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null;
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null) {
            return false;
        }
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static float pickPositive(float primary, float secondary) {
        return Math.max(Math.max(primary, 0f), Math.max(secondary, 0f));
    }

    private static float applyDeadZone(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= DEAD_ZONE) {
            return 0f;
        }
        return value * (magnitude - DEAD_ZONE) / (1f - DEAD_ZONE);
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
