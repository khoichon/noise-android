package com.khoichon.noise

/** One fire alarm. All of a round's alarms exist at once; a pulled one stays gone. */
class Alarm(val id: Int, var x: Float, var y: Float) {
    var pulled = false
    var visible = false
    var opacity = 0f
}

/** Everything the takeover view draws. Written by the game engine, read by [SceneView]. */
class SceneState {
    var noiseVisible = false
    var noiseX = 0f
    var noiseY = 0f
    var noiseAnim = "pausecursor.png"
    val alarms = ArrayList<Alarm>()
}

/** Touches on the takeover / TV, reported back to the engine. */
interface OverlayListener {
    fun onTouchDown(x: Float, y: Float)
    fun onTouchMove(x: Float, y: Float)
    fun onTvMoved()
}
