package dev.kairo.myra

import android.graphics.Color

/**
 * Myraa colours in one place. Dark background with a warm orange-gold glow, like the home-screen look.
 * Change a value here and every screen follows.
 */
object Palette {
    val BG = 0xFF0B0C10.toInt()        // main background
    val CARD = 0xFF14161B.toInt()      // cards and panels
    val FIELD = 0xFF0F1115.toInt()     // text fields
    val LINE = 0xFF23262D.toInt()      // dividers
    val INK = 0xFFF2EFEA.toInt()       // main text
    val DIM = 0xFF8E919A.toInt()       // secondary text
    val FAINT = 0xFF5A5E68.toInt()     // hints
    val ORANGE = 0xFFFF8F2E.toInt()    // accent: buttons, switches, links
    val GOLD = 0xFFFFC870.toInt()      // glow highlight, used with ORANGE in gradients
    val ORANGE_DEEP = 0xFF2A1A0C.toInt()   // soft orange background for selected items
    val ORANGE_EDGE = 0xFF4A2C10.toInt()   // orange border
    val GOOD = 0xFF5BD08A.toInt()      // success
    val BAD = 0xFFFF6B5A.toInt()       // error or danger
    val DANGER = Color.parseColor("#B3261E")
}
