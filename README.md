# Aiboo – AI Agent for Android 🤖

**Aiboo** is a Kotlin-based Android AI agent that can understand natural language commands and perform tasks on your device. It is powered by **Google Gemini**, uses **Lottie** for futuristic animations, and **Prexocore** to simplify Android development.

---

## 🌟 Key Features (High-Level)

Aiboo can perform these tasks in the background after understanding your query:

* Order food
* Book rides
* Post on Twitter/X
* Call or text contacts on different platforms
* Launch apps or websites
* Capture photos and screenshots
* Perform quick actions (flashlight, volume, brightness, vibrate, etc.)
* Set alarms and reminders
* Delete, copy, move, and rename files
* Analyse data like photos, contacts, SMS, call history, files, or calendar events

> All tasks are executed in the background automatically once Aiboo understands the command.

---

## 🏗 Architecture (High-Level)

* **Input:** Voice or text query
* **Agent Layer:** Aiboo interprets your intent and required details
* **Task Execution:** Action is performed on the device silently in the background
* **Feedback:** Only the `message` (short status) is shown to the user with Lottie animations; task executes in the background

---

## ⚙️ Installation & Setup

### Requirements

* Android Studio (latest)
* Android device or emulator (API 24+)
* Google Gemini API key from [AI Studio](https://aistudio.google.com)

### Steps

1. Clone the repository:

   ```bash
   git clone https://github.com/binarybeam/Aiboo.git
   ```
2. Open in **Android Studio**, sync Gradle.
3. Run on your device.
4. On first launch, enter your **Gemini API key** and optionally a **model name**.

   * The key is stored **locally**.
   * To change it, **clear app data** or **reinstall** the app.

---

## 🎤 Example Queries & Responses

When you speak or type a command, Aiboo shows a **short message** on screen and performs the task in the background.

### Food Ordering

* **Query:** "Order a veg pizza under 200"
* **Message shown:** "Ordering your food... 🍕"
* **Task:** Food ordering link opens in background WebView.

### Ride Booking

* **Query:** "Book an auto from Jayanagar to Indiranagar"
* **Message shown:** "Booking your ride... 🛺"
* **Task:** Opens ride booking page in background.

### Setting Reminder

* **Query:** "Remind me tomorrow at 7 AM to workout"
* **Message shown:** "Setting reminder... ⏰"
* **Task:** Reminder scheduled using AlarmManager.

### Quick Action

* **Query:** "Turn on flashlight"
* **Message shown:** "Flashlight turned on 💡"
* **Task:** Device flashlight activated.

### File Operation

* **Query:** "Delete my Aiboo folder"
* **Message shown:** "Deleting files... 🗑️"
* **Task:** Files deleted in background.

> Every task triggers a short on-screen message, while the actual action happens in the background.

---

## 🔐 Permissions

Aiboo requests permissions only when required:

* Microphone for voice
* Contacts, SMS, Call logs, Calendar for queries
* Camera and storage for media capture
* System permissions for brightness, volume, and reminders

---

## 🔒 Security & Privacy

* API key is **stored locally** only
* No silent data upload
* File operations and sensitive actions are explicit and user-driven

---

## 🤝 Contribution Guidelines

1. Fork the repository
2. Create a feature branch
3. Implement and test your change
4. Submit a Pull Request

---

## 📜 License

Open-source under the **Apache-2.0 License**.

> **Aiboo** – Speak, and let your phone do everything else in the background ✨
