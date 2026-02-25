package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.math.Mathf
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.XServer
import java.util.Timer
import java.util.TimerTask

/**
 * Standalone handler for physical controller input that works independently of view visibility.
 * Applies profile bindings to convert physical controller input into virtual gamepad state.
 */
class PhysicalControllerHandler(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val onOpenNavigationMenu: (() -> Unit)? = null
) {
    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private var mouseMoveTimer: Timer? = null

    fun setProfile(profile: ControlsProfile?) {
        this.profile = profile
        Log.d(TAG, "PhysicalControllerHandler: Profile set to ${profile?.name}")

        // Cancel mouse movement timer if profile is null
        if (profile == null) {
            mouseMoveTimer?.cancel()
            mouseMoveTimer = null
            mouseMoveOffset.set(0f, 0f)
        }
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
        mouseMoveOffset.set(0f, 0f)
    }

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (profile == null || event.repeatCount != 0) return false

        // Same slot guard as onGenericMotionEvent: only handle P1 (slot 0 / unassigned) here.
        // P2+ key events fall through to WinHandler which routes them to the correct slot.
        val winHandler = xServer?.winHandler
        val slot = if (winHandler != null) winHandler.getSlotForDevice(event.deviceId) else -1
        if (slot > 0) return false

        val controller = profile?.getController(event.deviceId)
        if (controller != null) {
            val controllerBinding = controller.getControllerBinding(event.keyCode)
            if (controllerBinding != null) {
                // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                if ((event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                    (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2) &&
                    deviceHasTriggerAxis(event.device, event.keyCode)
                ) {
                    return true
                }
                // Many controllers emit BOTH KEYCODE_DPAD_* key events and AXIS_HAT_X/Y motion events
                // for the same physical d-pad press. When the device exposes HAT axes, suppress the
                // key event — updateStateFromMotionEvent() already writes state.dpad[] from the HAT,
                // so processing the key event here too would duplicate the directional input.
                if (event.keyCode in DPAD_KEY_CODES &&
                    controllerBinding.binding.isDpad &&
                    deviceHasHatAxis(event.device)
                ) {
                    return true
                }
                val offset = if (event.action == KeyEvent.ACTION_DOWN &&
                    (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2)
                ) 1f else 0f
                handleInputEvent(controllerBinding.binding, event.action == KeyEvent.ACTION_DOWN, offset, deviceId = event.deviceId)
                return true
            }
        }
        return false
    }

    private fun deviceHasTriggerAxis(device: InputDevice?, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 ->
                hasMotionRange(device, MotionEvent.AXIS_LTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_BRAKE)
            KeyEvent.KEYCODE_BUTTON_R2 ->
                hasMotionRange(device, MotionEvent.AXIS_RTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_GAS)
            else -> false
        }
    }

    private fun deviceHasHatAxis(device: InputDevice?): Boolean {
        return hasMotionRange(device, MotionEvent.AXIS_HAT_X) || hasMotionRange(device, MotionEvent.AXIS_HAT_Y)
    }

    private val Binding.isDpad: Boolean
        get() = this == Binding.GAMEPAD_DPAD_UP || this == Binding.GAMEPAD_DPAD_DOWN ||
                this == Binding.GAMEPAD_DPAD_LEFT || this == Binding.GAMEPAD_DPAD_RIGHT

    companion object {
        private val DPAD_KEY_CODES = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }

    private fun hasMotionRange(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
            device.getMotionRange(axis) != null
    }

    /**
     * Handle physical controller analog stick and trigger events.
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (profile == null) return false

        // Determine the player slot for this device before touching any shared state.
        // The wildcard ExternalController in the profile is a *shared* object — its .state
        // is mutated in-place by updateStateFromMotionEvent. If a P2+ device's event reaches
        // here first and overwrites that shared state with zeros, P1's held inputs get wiped.
        val winHandler = xServer?.winHandler
        val slot = if (winHandler != null) winHandler.getSlotForDevice(event.deviceId) else -1

        if (slot > 0) {
            // P2+ device: let WinHandler handle it through its own ExternalController so
            // the shared profile wildcard state is never touched.
            return false
        }

        // Slot 0 / unassigned → P1 path: safe to use the shared profile wildcard.
        val controller = profile?.getController(event.deviceId)
        if (controller != null && controller.updateStateFromMotionEvent(event)) {
            // Process trigger buttons (L2/R2)
            var controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
            if (controllerBinding != null) {
                handleInputEvent(
                    controllerBinding.binding,
                    controller.state.triggerL > 0f,
                    controller.state.triggerL,
                    deviceId = event.deviceId
                )
            }

            controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
            if (controllerBinding != null) {
                handleInputEvent(
                    controllerBinding.binding,
                    controller.state.triggerR > 0f,
                    controller.state.triggerR,
                    deviceId = event.deviceId
                )
            }

            // Process analog stick input
            processJoystickInput(controller, event.deviceId)
            return true
        }
        return false
    }

    /**
     * Create a timer for continuous mouse movement injection.
     * Runs at 60 FPS, injecting mouse deltas based on mouseMoveOffset.
     */
    private fun createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            mouseMoveTimer = Timer()
            mouseMoveTimer?.schedule(object : TimerTask() {
                override fun run() {
                    // Skip injection if movement is below 8% deadzone to save CPU cycles
                    val magnitude = Math.sqrt((mouseMoveOffset.x * mouseMoveOffset.x + mouseMoveOffset.y * mouseMoveOffset.y).toDouble())
                    if (magnitude < 0.08) return

                    // Look up cursor speed dynamically so it updates when profile changes
                    val cursorSpeed = profile?.cursorSpeed ?: 1f
                    val deltaX = (mouseMoveOffset.x * 10 * cursorSpeed).toInt()
                    val deltaY = (mouseMoveOffset.y * 10 * cursorSpeed).toInt()
                    xServer?.injectPointerMoveDelta(deltaX, deltaY)
                }
            }, 0, 1000 / 60)
        }
    }

    /**
     * Process analog stick input and apply bindings.
     * Extracted from InputControlsView.processJoystickInput()
     *
     * NOTE: AXIS_HAT_X and AXIS_HAT_Y are intentionally excluded here.
     * [ExternalController.updateStateFromMotionEvent] already writes state.dpad[] directly
     * from raw HAT values before this is called. Re-processing the HAT axes through bindings
     * would duplicate every directional input — once from the KeyEvent (KEYCODE_DPAD_*) path
     * and once again here from the MotionEvent HAT path.
     */
    private fun processJoystickInput(controller: ExternalController, deviceId: Int) {
        // Reset mouse movement offset at the start - contributions will be added during processing
        mouseMoveOffset.set(0f, 0f)

        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            // AXIS_HAT_X and AXIS_HAT_Y intentionally omitted — handled by updateStateFromMotionEvent
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
        )

        for (i in axes.indices) {
            var controllerBinding: ExternalControllerBinding?
            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
                controllerBinding = controller.getControllerBinding(keyCode)
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding, true, values[i], deviceId = deviceId)
                }
            } else {
                controllerBinding = controller.getControllerBinding(
                    ExternalControllerBinding.getKeyCodeForAxis(axes[i], 1.toByte())
                )
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding, false, values[i], deviceId = deviceId)
                }
                controllerBinding = controller.getControllerBinding(
                    ExternalControllerBinding.getKeyCodeForAxis(axes[i], (-1).toByte())
                )
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding, false, values[i], deviceId = deviceId)
                }
            }
        }
    }

    /**
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    private fun handleInputEvent(binding: Binding, isActionDown: Boolean, offset: Float = 0f, deviceId: Int = 0) {
        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val slotIndex = if (winHandler != null && deviceId != 0) winHandler.getSlotForDevice(deviceId) else -1

            // For P2-PX(slot > 0), write to that controller's own state
            val state: GamepadState? = if (slotIndex > 0) {
                winHandler?.getExtraController(slotIndex)?.state
            } else {
                profile?.gamepadState
            }

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            state.triggerL = offset
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), offset > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            state.triggerR = offset
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), offset > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                } else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_RIGHT,
                        Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                        }
                        else -> {}
                    }
                }

                if (winHandler != null) {
                    if (slotIndex > 0) {
                        // P2-PX: write to the slot's .mem file
                        winHandler.sendSlotMemState(slotIndex)
                    } else {
                        // Assume P1
                        val controller = winHandler.currentController
                        if (controller != null) {
                            controller.state.copy(state)
                        }
                        winHandler.sendGamepadState()
                        winHandler.sendVirtualGamepadState(state)
                    }
                }
            }
        } else {
            // Handle special bindings
            if (binding == Binding.OPEN_NAVIGATION_MENU) {
                if (isActionDown) {
                    Log.d(TAG, "Opening navigation menu from controller binding")
                    onOpenNavigationMenu?.invoke()
                }
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                // Handle horizontal mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_LEFT) -1f else 1f
                    mouseMoveOffset.x += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                // Handle vertical mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_UP) -1f else 1f
                    mouseMoveOffset.y += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
            } else {
                // For keyboard/mouse button bindings, inject into XServer
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonPress(pointerButton)
                    } else {
                        xServer?.injectKeyPress(binding.keycode)
                    }
                } else {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonRelease(pointerButton)
                    } else {
                        xServer?.injectKeyRelease(binding.keycode)
                    }
                }
            }
        }
    }
}
