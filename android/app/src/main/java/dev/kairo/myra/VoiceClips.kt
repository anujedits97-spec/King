package dev.kairo.myra

/**
 * The few things the phone says by itself, recorded in MYRA's voice (en-GB-RyanNeural) so they match
 * what the laptop sends. Made by android/voice-clips.mjs - run it again after changing a text.
 */
object VoiceClips {
    class Clip(val res: Int, val words: IntArray)

    private val clips = mapOf(
        "At your service. MYRA is online and ready when you are." to Clip(R.raw.voice_hello, intArrayOf(100, 325, 462, 2012, 2488, 2612, 3025, 3175, 3400, 3612, 3762)),
        "Starting a new conversation." to Clip(R.raw.voice_new_chat, intArrayOf(100, 688, 738, 1000)),
        "Your laptop isn't connected right now. Make sure it's on and MYRA is running on it." to Clip(R.raw.voice_laptop_offline, intArrayOf(100, 312, 812, 1050, 1525, 1812, 3088, 3400, 3588, 3788, 3950, 4100, 4562, 4712, 5062, 5200)),
        "The laptop has never connected. Run MYRA on the laptop first." to Clip(R.raw.voice_laptop_never, intArrayOf(100, 250, 762, 912, 1188, 2688, 3038, 3512, 3638, 3712, 4150)),
        "MYRA isn't paired with your laptop yet. Open the app and scan the QR code from the laptop." to Clip(R.raw.voice_not_paired, intArrayOf(100, 588, 850, 1150, 1288, 1412, 1925, 3125, 3450, 3575, 3775, 3900, 4162, 4250, 4675, 5012, 5175, 5250)),
        "I couldn't reach the laptop. Check your internet connection." to Clip(R.raw.voice_no_reach, intArrayOf(100, 250, 550, 788, 888, 2362, 2662, 2862, 3288)),
        "The laptop took too long." to Clip(R.raw.voice_too_long, intArrayOf(100, 250, 712, 900, 1150)),
        "Speech recognition needs the internet right now." to Clip(R.raw.voice_no_internet, intArrayOf(100, 550, 1175, 1475, 1600, 2062, 2312)),
        "The microphone is busy." to Clip(R.raw.voice_mic_busy, intArrayOf(100, 250, 888, 1038)),
    )

    fun forText(text: String): Clip? = clips[text]
}
