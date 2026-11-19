package com.anything2game.app.ui.character

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The motion every character shares, independent of how it is drawn: breathing, blinking, squash-and-stretch
 * on mood changes, hops, lean with the swipe card (with a lagging copy for secondary motion such as an antenna),
 * swelling while the player holds to object, and a poke reaction. Art (procedural, images, Rive) only reads these values.
 */
class AvatarMotion internal constructor(
    val mood: Mood,
    val breath: Float, val beat: Float, val blink: Float,
    val squash: Float, val hop: Float,
    val lean: Float, val lag: Float, val charge: Float,
    val look: Offset,
    val pokeModifier: Modifier,
)

@Composable
fun rememberAvatarMotion(mood: Mood, lookAt: Offset, useTilt: Boolean, pokeable: Boolean = true): AvatarMotion {
    val signals = LocalAvatarSignals.current
    val reduced = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var poked by remember { mutableStateOf(false) }
    val shown = if (poked) Mood.SURPRISED else mood

    // Looping motion (breath, beat, blink) only when animations are on; reduced motion = a still character (D3).
    var breath = 0f; var beat = 0f; var blink = 0f
    if (!reduced) {
        val inf = rememberInfiniteTransition(label = "avatar")
        breath = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath").value
        beat = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Restart), label = "beat").value
        blink = inf.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3900
            0f at 0; 0f at 3560; 1f at 3640; 0f at 3780
        }), label = "blink").value
    }

    val squash = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        when (shown) {
            Mood.HAPPY, Mood.SURPRISED -> {
                launch { hop.animateTo(-0.14f, tween(150, easing = FastOutSlowInEasing)); hop.animateTo(0f, spring(0.45f, 380f)) }
                squash.snapTo(-0.16f); squash.animateTo(0f, spring(0.3f, 260f))
            }
            Mood.OBJECT -> { hop.animateTo(0f); squash.snapTo(0.2f); squash.animateTo(-0.04f, spring(0.35f, 420f)) }
            Mood.DEFLATED -> { hop.animateTo(0f); squash.animateTo(0.13f, tween(520)) }
            Mood.SLEEPY -> { hop.animateTo(0f); squash.animateTo(0.06f, tween(800)) }
            else -> { hop.animateTo(0f); squash.animateTo(0f, spring(0.5f, 300f)) }
        }
    }
    val lean by animateFloatAsState(signals.lean, spring(0.6f, 400f), label = "lean")
    val lag by animateFloatAsState(signals.lean, spring(0.25f, 60f), label = "lag")
    val charge by animateFloatAsState(signals.charge, tween(80), label = "charge")
    val tilt = if (useTilt && !reduced) rememberTilt().value else Offset.Zero
    val look = when (shown) {
        Mood.THINKING -> Offset(-0.7f, -0.8f)
        Mood.SLEEPY, Mood.HAPPY, Mood.CELEBRATE -> Offset.Zero
        else -> Offset((lookAt.x + tilt.x + lean * 0.8f).coerceIn(-1f, 1f), (lookAt.y + tilt.y).coerceIn(-1f, 1f))
    }
    val poke = if (!pokeable) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            poked = true
            scope.launch { squash.snapTo(0.22f); squash.animateTo(0f, spring(0.3f, 300f)); delay(450); poked = false }
        }
    }
    return AvatarMotion(shown, breath, beat, blink, squash.value, hop.value, lean, lag, charge, look, poke)
}

/** Gravity sensor → where the eyes look. Low-pass filtered; nothing registered when the device has no sensor. */
@Composable
fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = (-e.values[0] / 4f).coerceIn(-1f, 1f)
                val y = ((7f - e.values[1]) / 4f).coerceIn(-1f, 1f)
                val p = state.value
                state.value = Offset(p.x + (x - p.x) * 0.2f, p.y + (y - p.y) * 0.2f)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

/** Respect the system "remove animations" setting (D3). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
