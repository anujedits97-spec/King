"""MYRA's voice: turns replies into speech with Microsoft's free neural voices (edge-tts).

pc/worker.mjs keeps this running: one JSON request per line on stdin,
    {"id": 1, "text": "...", "voice": "en-GB-RyanNeural", "rate": "+0%", "pitch": "+0Hz"}
one JSON answer per line on stdout,
    {"id": 1, "audio": "<base64 mp3>", "words": [start of each word in ms]}   or   {"id": 1, "error": "..."}

To hear a voice:  python tts.py --say "Good evening, sir." sample.mp3 [voice]
Needs:  python -m pip install --user edge-tts
"""
import asyncio
import base64
import json
import sys

import edge_tts

DEFAULT_VOICE = "hi-IN-SwaraNeural"  # Myraa: warm Hindi/Hinglish female voice


async def speak(text, voice=None, rate=None, pitch=None):
    c = edge_tts.Communicate(text, voice or DEFAULT_VOICE, rate=rate or "+0%", pitch=pitch or "+0Hz", boundary="WordBoundary")
    audio = bytearray()
    words = []
    async for chunk in c.stream():
        if chunk["type"] == "audio":
            audio += chunk["data"]
        elif chunk["type"] == "WordBoundary":
            words.append(round(chunk["offset"] / 10_000))  # 100 ns units -> ms
    return bytes(audio), words


async def serve():
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            break
        try:
            req = json.loads(line)
        except ValueError:
            continue
        try:
            audio, words = await asyncio.wait_for(speak(req["text"], req.get("voice"), req.get("rate"), req.get("pitch")), 30)
            out = {"audio": base64.b64encode(audio).decode(), "words": words}
        except Exception as e:  # no internet, voice service down, bad voice name...
            out = {"error": f"{type(e).__name__}: {e}"}
        out["id"] = req.get("id")
        sys.stdout.write(json.dumps(out) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--say":
        audio, _ = asyncio.run(speak(sys.argv[2], sys.argv[4] if len(sys.argv) > 4 else None))
        with open(sys.argv[3], "wb") as f:
            f.write(audio)
    else:
        asyncio.run(serve())
