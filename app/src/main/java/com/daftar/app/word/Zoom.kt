package com.daftar.app.word

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Two-finger pinch for document viewers. It watches the Initial pass and only acts — and only consumes events — while
 * two or more pointers are down, so one-finger scrolling, flinging, links and text selection (long-press) keep working.
 * [onPinch] gets the incremental zoom factor and the centroid; [onEnd] runs when the pinch is over.
 */
@Composable
fun Modifier.pinchToZoom(onPinch: (zoom: Float, centroid: Offset) -> Unit, onEnd: () -> Unit): Modifier {
    val pinch by rememberUpdatedState(onPinch)
    val end by rememberUpdatedState(onEnd)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var swallow = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val down = e.changes.count { it.pressed }
                if (down >= 2) {
                    pinching = true
                    swallow = true
                    val z = e.calculateZoom()
                    val c = e.calculateCentroid(useCurrent = true)
                    if (z != 1f && c.isSpecified) pinch(z, c)
                    e.changes.forEach { if (it.positionChanged()) it.consume() }
                } else {
                    if (pinching) { end(); pinching = false }
                    // The finger left on the glass after a pinch must not turn into a scroll jump: swallow it until lifted.
                    if (swallow) e.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (e.changes.none { it.pressed }) break
            }
            if (pinching) end()
        }
    }
}

/** Ctrl + mouse wheel / touchpad scroll zooms (×1.1 per notch) instead of scrolling. */
@Composable
fun Modifier.ctrlWheelZoom(onZoom: (factor: Float, position: Offset) -> Unit): Modifier {
    val cb by rememberUpdatedState(onZoom)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Scroll && e.keyboardModifiers.isCtrlPressed) {
                    val dy = e.changes.fold(0f) { acc, ch -> acc + ch.scrollDelta.y }
                    if (dy != 0f) cb(if (dy < 0f) 1.1f else 1f / 1.1f, e.changes.first().position)
                    e.changes.forEach { it.consume() }
                }
            }
        }
    }
}
