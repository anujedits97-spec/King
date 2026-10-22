# 🤖 Myraa for Your Phone — Your AI Assistant

<div align="center">

### ✨ Your Personal Android Voice & Chat Assistant

**A smarter phone experience — voice, chat, and useful tools in one app.**

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![AI Engines](https://img.shields.io/badge/AI-Gemini%20%7C%20Groq%20%7C%20OpenRouter-7C3AED?style=for-the-badge)
![Interface](https://img.shields.io/badge/Interface-Voice%20%26%20Chat-0EA5E9?style=for-the-badge)
![Termux](https://img.shields.io/badge/Termux-Not%20Required-16A34A?style=for-the-badge)

> 🚀 **Meet Myraa:** an Android assistant that can chat, respond to voice, help with phone actions, work with files, and support planning tasks. Choose an AI engine and configure the keys you need.

</div>

---

## 📚 Table of Contents

- [✨ Overview](#-overview)
- [⚠️ Project Status](#️-project-status)
- [⚡ Quick Start](#-quick-start)
- [🎙️ Talking to Myraa](#️-talking-to-myraa)
- [💬 Using It](#-using-it)
- [🧰 What Myraa Can Do](#-what-myraa-can-do)
- [🧭 Tool Catalogue](#-tool-catalogue)
- [🔑 Keys and Accounts](#-keys-and-accounts)
- [🔐 Privacy and Safety](#-privacy-and-safety)
- [🛠️ Building the APK](#️-building-the-apk)
- [🧪 Testing Checklist](#-testing-checklist)
- [🗺️ Roadmap — Not Yet Available](#️-roadmap--not-yet-available)
- [🗂️ Legacy Components](#️-legacy-components)
- [📄 License](#-license)

---

## ✨ Overview

Myraa is designed to bring voice and chat assistance directly into an Android app. It does not require Termux. You can select Gemini, Groq, or OpenRouter as the AI engine and use the phone features supported by the app.

---

## ⚠️ Project Status

> **Experimental features:** Several v2.x features (call handling, anti-theft, the 2D face, screen OCR, document creation, the Maya and Home dashboard additions) were written outside Android Studio and have **not been compiled or tested on a phone**. Only `DocMaker.kt` was compiled, and its `.pptx` and `.docx` output was checked with `python-pptx` and LibreOffice. Build the APK and test these features before relying on them.

---

## ⚡ Quick Start

1. Install the APK built by `.github/workflows/build-apk.yml`, or build it yourself (see "Building the APK").
2. Open the app, go to **Settings → AI engine**, pick Gemini, Groq or OpenRouter, paste the free key, and tap **Save**.
3. Allow **Contacts, calls and texts** and **Read notifications**.
4. Optional: turn on **Wake word** (Settings) to talk hands-free.

---

## 🎙️ Talking to Myraa

There are four ways to start a conversation:

- **Tap the orb** on the home screen. This always works.
- **Say the wake word.** The default is **"Myra"**. The offline listener also accepts "mira", "maira", "meera" and "myraa". You can choose a different word in **Settings → Wake word**, or say "wake word maya kar do". "Hey" or "okay" before the word also works. Wake word is on or off in Settings.
- **Use the quick tile or shortcut.**
- **Type** in the question box. No wake word is needed.

Other voice controls:

- **Stop:** tap the orb again, or say "stop". Stop, cancel and thanks always work. An extra stop phrase can be set in Settings (for example "bye bye"), or by voice.
- **New topic:** say "new conversation". Myraa also starts fresh after 30 quiet minutes.
- **Language:** say "Bangla mein baat karo" or set it in Settings → Language. Bangla and Tamil need those voices installed on the phone.
- **Move the orb:** drag it up or down. Hold it to open the app.

The wake word only starts a **voice conversation**. Calls, anti-theft, notifications, reminders, routines, check-ins and SOS run on their own triggers and do not need the wake word.

---

## 💬 Using It

- **Type or talk:** the question box takes typed text, and the orb takes voice.
- **Attach a file:** tap the button left of the question box and pick any file. Type a question and send, or send without one to get a summary.
  - Text, code, CSV, JSON, Markdown and logs are read on the phone (UTF-8, UTF-16 or Latin-1).
  - **PDF:** text is read on the phone. A scanned PDF (photos of pages) is sent as page pictures, the first 6 pages, for the AI to look at.
  - **Word, Excel, PowerPoint** (`docx xlsx pptx`), **OpenDocument** (`odt ods odp`), **EPUB, RTF, HTML** and **Jupyter notebooks** are read for their text. Old `doc xls ppt` files give rough text only, so save them in the new format for exact text.
  - **Pictures** (`jpg png gif webp heic`) are shrunk on the phone and shown to the AI. Groq cannot see pictures.
  - Other files (video, audio, apps) are saved on the phone so Myraa knows they are there, but she cannot read inside them.
  - Office files, pictures and scanned PDFs need **AI engine → This phone** mode. Text and text PDFs also work on the laptop brain.
  - **Long files:** about 15,000 characters go into the question. The full text is saved next to the file, and Myraa can read on with her file tools.
  - **Limits:** 25 MB per file. The newest 40 attachments are kept in `~/myra-workspace/attachments/`.
  - **Zip files** work differently. Myraa can list and read their contents.
- **Move the orb:** drag it up or down. Hold it to open the app.

**Example requests:**
- "Aaj ka mausam kaisa hai Chennai mein?" (weather)
- "Torch on karo." (flashlight)
- "YouTube kholo." (open app)
- "Papa ko WhatsApp karo." (send message)
- "Mummy ko call karo." (call)
- "Ek cafe ki website banao." (build website)
- "Is screen par kya likha hai?" (screen OCR)
- "Diary mein likho ..." (journal)
- "Ek presentation banao AI trends par." (make deck)

---

## 🧰 What Myraa Can Do

**Phone**
- Calls and texts, contacts lookup, and notifications.
- Open apps, flashlight, and screen control. Screen OCR (ML Kit, English and Hindi) reads the screen and gives positions, so Myraa can tap what it reads.
- **Takes calls for you:** when a call comes in, it can pick up on loudspeaker, ask who is calling and why, hang up, and leave a summary notification. Settings has "Myraa takes my calls" and "Only for unknown numbers". This works on a best-effort basis, and some phones block the microphone during calls.
- **Anti-theft guard:** a siren on the alarm volume and a front-camera photo when the phone is moved or the charger is pulled. Disarm from the notification, which asks for the screen lock. It does not detect wrong unlock attempts.
- **SOS** and **routines**. A routine is a set of steps you can run by name.
- **Triggers:** Myraa can act when a set condition happens.
- **Clipboard:** read or write the clipboard, and translate, summarize, explain or fix what is copied.

**Information and planning**
- Web search, news, weather, YouTube summaries, flight search, and reading web pages.
- Reminders, nicknames, memory, and a morning briefing.
- **Daily topic monitors:** watch a topic each day and get a notification with updates.
- **Check-ins:** friendly notifications at the hours you set.
- **Session memory:** Myraa can save and recall the session, and recall memory word by word.
- **Lists and places:** keep lists and save places.

**Documents and files**
- Attach any file: text, code, CSV, JSON, Markdown, logs, PDF, Word, Excel, PowerPoint, OpenDocument, EPUB, RTF, HTML, Jupyter notebooks, pictures, and zip files.
- **Make decks and reports:** `make_deck` and `make_report` research a topic and write a real `.pptx` or `.docx` into `Documents/Myra-Docs`.
- **File manager** for the whole phone (Settings → File manager). It has folders, quick places (Download, DCIM, Pictures, Documents, Music, Movies, SD card), search, sort, open, share, rename, new folder, copy, move, and delete to a Trash folder. On Android 11 and later, turn on the **All files access** switch. Android 10 cannot browse there. Myraa's own tools still reach only her workspace folder.

**Code and projects**
- `code_helper`, `code_project` (plan, write all files, review, fix, zip), and `project_*` tools that edit real code in `Documents/Myra-Projects`.
- `code_run` runs code.
- `deploy_guide` for hosting help.

**Integrations** (each needs its own key or login, set in Settings)
- **Email (SMTP):** send email with your own account.
- **Messages:** SMS read and send, WhatsApp open and send, Telegram (check, send, open, and a bot).
- **Notes and tasks:** Notion (search, read, add note), Todoist (list, add, complete), Linear (my issues, issue details, create issue), Google Drive.
- **Code hosting:** GitHub, GitLab (projects, issues, create issue).
- **Servers:** SSH (detect, inspect, install, deploy, upload, upload zip, clone, logs, status, trust, and recipes).
- **Social posting:** Facebook Page, Instagram Business and YouTube, with a preview and your approval before anything is posted.
- **Pictures:** picture making uses the picture key set under Keys and accounts.
- **Web search:** Tavily (the best option) with DuckDuckGo and Wikipedia as the free fallback.

**Look and personality**
- A 2D face (Settings → "Show Myraa's face") that blinks and moves her mouth while speaking.
- A female Hindi-English voice. By default it uses an installed Hindi (hi-IN) Google voice, falling back to Indian English, then British.
- **Speaking styles** (`voice_settings` tool): sweet (the usual Myraa), calm, friendly, professional, energetic.
- **Personalities** of the answers can be set to match your mood.
- Bangla and Tamil listening and speaking, if the phone has those voices installed.

**Home dashboard, Journal, Study and Scan**
- **Greeting** with your name, and a **day streak** chip. Open the app on consecutive days to grow it. Points come from opening the app, diary or study notes, and focus timers or scans. Nothing is locked behind them.
- **Weather card** from Open-Meteo (no key). Type a city, or use your phone's last known location. Cached for 30 minutes.
- **Today card:** today's date and your next calendar event. Tap again to see the next 3 days.
- **Mood card:** a label you pick yourself (Happy, Calm, Tired, Stressed, Low). If you pick nothing, a rough guess comes from words in today's diary, with no AI. It is not a medical or mental-health reading.
- **Music:** asks Myraa to play something that fits your mood.
- **Study:** study notes (type or speak) and a focus timer of 15, 25 or 45 minutes.
- **Journal:** a private diary. Type or speak, and entries are listed by day. Myraa never writes passwords, PINs, keys or card numbers into it.
- **Scan:** take photos or pick from the gallery, up to many pages. **Padh ke batao** sends up to 6 pages to Myraa to read out (needs "This phone" mode). **Text nikalo** reads the words on the phone (ML Kit, English and Hindi). **PDF banao** makes one PDF to share or open.

**App lock and privacy**
- **App lock:** 4 to 8 digit PIN, or the **Fingerprint** button (Android 9 and later, uses the phone's own fingerprint or face screen). Only a salted PBKDF2 hash of the PIN is stored. Five wrong tries lock the box for a minute. The screens are hidden until unlocked again, and after 30 seconds away they lock again.
- **Privacy check:** one screen that shows which permissions are on, which Myraa features are switched on, and where your words go.

---

## 🧭 Tool Catalogue

Myraa uses these tools in the background. You do not need to name them. Just ask in plain words.

| Area | Tools |
|---|---|
| Phone | `call`, `call_log`, `call_control`, `sms_read`, `sms_send`, `flashlight`, `post_notification`, `timer_set` |
| Screen | `screen_look`, `screen_read`, `screen_ocr`, `screen_tap`, `screen_type`, `screen_swipe`, `screen_key` |
| Clipboard | `clipboard_read`, `clipboard_write`, `clipboard_assist` |
| Information | `news_search`, `weather_report`, `flight_search`, `read_webpage`, `youtube_info`, `youtube_search`, `youtube_summarize` |
| Monitors and check-ins | `monitor_add`, `monitor_list`, `monitor_check`, `monitor_remove`, `checkin_set` |
| Memory and planning | `memory`, `nickname`, `briefing`, `session_save`, `routine`, `routine_run`, `routine_remove`, `trigger_add`, `trigger_list`, `trigger_delete`, `lists`, `journal_add`, `journal_read`, `study` |
| Documents | `make_deck`, `make_report` |
| Code | `code_helper`, `code_project`, `code_run`, `project_*`, `deploy_guide` |
| Integrations | `email_send`, `whatsapp_send`, `whatsapp_open`, `telegram_*`, `notion_*`, `todoist_*`, `linear_*`, `gitlab_*`, `drive_*`, `ssh_*`, `facebook`, `instagram` |
| Settings and safety | `voice_settings`, `theft_guard` |

---

## 🔑 Keys and Accounts

All keys are typed in the app under **Settings → Keys and accounts**. They are sent to the brain, and the app does not show them back. An empty box keeps the saved value. "Remove keys" clears a section. Each value is checked before saving. If one is wrong, nothing changes and the screen says what to fix.

The sections are: email (SMTP), picture making, Facebook Page, Instagram Business, YouTube, Meta API version, and web search (Tavily).

- **Gemini:** free key from Google AI Studio (aistudio.google.com, API keys). Default model `gemini-2.5-flash`. Used for chat.
- **Groq:** key from console.groq.com (starts with `gsk_`). Default model `openai/gpt-oss-120b`. Chat only, so it cannot look at pictures.
- **OpenRouter:** free key from openrouter.ai.
- **Tavily:** web search key from app.tavily.com (starts with `tvly-`). Without it, DuckDuckGo and Wikipedia are used. If a page can't be opened but Tavily gave a summary, that summary is used and marked as a summary only.

---

## 🔐 Privacy and Safety

- **No accounts and no servers of ours.** The app and brain run on your phone. The outside services are the AI engine you chose, plus any integrations you set up.
- **Speech recognition** uses the phone's own recognizer. The wake word uses an offline model (about 36 MB) that is downloaded once.
- **The brain only listens on the phone itself** (`127.0.0.1`) and needs a token.
- **Your keys are not shown back** after saving, and are not written to any file in this repository.
- **Sensitive features are powerful.** Calls, screen control, SMS, anti-theft and SOS can act on your phone. Turn on only what you need, and grant permissions knowingly.
- **App lock** hides the app screens. It locks screens only. The orb and the wake word keep working, and it is not encryption. If you forget the PIN, you have to clear the app's data, which also removes your keys.
- **Journal, study notes and scans** stay on the phone. Only a scan you ask Myraa to read goes to your AI engine.
- **Privacy check** in Settings shows which permissions and features are on and where your words go.

---

## 🛠️ Building the APK

The APK builds on GitHub Actions. See `.github/workflows/build-apk.yml` and `.github/README-SECRETS.md`. Android Studio also works, using the project in `android/`.

Test after building: the wake word (including changing the word), calls, anti-theft, the 2D face, screen OCR, document making, the PIN screen, the file manager, and the Home dashboard.

---

## 🧪 Testing Checklist

Before depending on a build, test the following on a real device:

- [ ] Wake word, including changing the wake word in Settings
- [ ] Call handling and microphone behavior during calls
- [ ] Anti-theft guard and disarming flow
- [ ] 2D face animation
- [ ] Screen OCR and screen-control actions
- [ ] Document creation (`.pptx` and `.docx`)
- [ ] App PIN and biometric screen
- [ ] File manager permissions and file access (rename, copy, trash)
- [ ] Home dashboard cards, the weather and calendar cards, and the focus timer
- [ ] Scan: camera, multi-page, and PDF share
- [ ] Bangla voice, if you use it

> 🔎 Test permissions and sensitive actions carefully. Availability and behavior can vary by Android version and device manufacturer.

---

## 🗺️ Roadmap — Not Yet Available

- **Default digital assistant:** making Myraa the phone's default assistant. This needs its own setup entry point, which the app does not have yet.
- **Gemini Live** and **WhatsApp voice notes**.
- **Wrong-PIN detection** for the anti-theft guard.
- **Social posts and WhatsApp group reports** with a full preview-and-approve flow. These will be added one at a time.
- **Reading Gmail.**

Nothing is sent or posted without your approval first.

---

## 🗂️ Legacy Components

The older Termux setup (`termux/`, `brain/*.mjs`) and the laptop bridge (`pc/`) are still in the repository for anyone who wants them. They are not needed for the app.

**Termux commands (legacy):**

```
myra start | stop | status     run the brain
myra token                     the token for the app
myra app                       install or update the app
myra pair <ip>:<port> <token>  let Myraa use your PC
myra ask "question"            ask from the terminal
myra new                       new conversation
myra model [name]              change the Gemini model
myra name <n> [address]        what Myraa calls you (default address: sir)
myra logs                      watch the brain
myra update                    get the newest version
```

---

## 📄 License

See `LICENSE`.

---

<div align="center">

### 💙 Built for a more helpful Android experience

**Myraa AI · Voice · Chat · Phone Tools**

_Review the project status and test experimental features before everyday use._

</div>
