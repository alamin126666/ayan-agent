# Ayan Agent — আয়ান এজেন্ট

Android ফোনের জন্য Jarvis-এর মতো AI ডেভেলপার এজেন্ট।
PRD v0.3 অনুযায়ী তৈরি — Kotlin + Jetpack Compose, Material 3
(সাদা বেস + বেগুনি গ্রেডিয়েন্ট, ডার্ক মোড নয়), বিল্ড পুরোটা GitHub Actions দিয়ে।

## যা যা আছে (ফুল প্রজেক্ট)

- **Loading পেজ** — বেগুনি গ্রেডিয়েন্ট লোগো, পালস অ্যানিমেশন, সেটআপ হয়েছে কি না চেক করে রাউট
- **Setup পেজ** — Python, PHP, HTML/CSS + Node, Java 17, Kotlin ইনস্টল; প্রোগ্রেস বার, Retry, লগ
- **Success পেজ** — সেটআপ শেষে Continue বাটন → Home
- **Home** — বটম নেভিগেশন: Chat / Terminal / Settings
- **Chat** — New Session, ফোল্ডার সিলেক্ট (SAF), ফুল PRD এডিটর, লাইভ থিংকিং/কমান্ড/উত্তর ফিড; কাজ শুধু সিলেক্ট করা ফোল্ডারেই
- **Terminal** — আসল রানটাইম টার্মিনাল: সরাসরি টাইপ, `cd`, `clear`, শেল কমান্ড লাইভ আউটপুট সহ
- **Settings** — বিল্ট-ইন প্রোভাইডার (OpenRouter, OpenAI, Anthropic, Gemini), কাস্টম প্রোভাইডার যোগ, মডেল সিলেক্ট, এক প্রোভাইডারের অনেকগুলো API key, লিমিট লাগলে অটো ফলব্যাক (সেশন/মেমোরি সেভ থাকে)
- **Code Editor** — VS Code-এর মতো: ফাইল ট্রি, ফাইলের নামে সার্চ, সব ফাইলে টেক্সট সার্চ, এক জায়গা থেকে সব ফাইলে রিপ্লেস (Undo সহ), ট্যাব, লাইন নাম্বার, সিনট্যাক্স হাইলাইট, অটো-সাজেশনের জায়গা, Git স্ট্যাটাস, রিসেন্ট ফাইল, Format বাটন
- **Data layer** — DataStore সেটিংস, Retrofit/OkHttp প্রোভাইডার ক্লায়েন্ট, মাল্টি-কি ফলব্যাক পুল
- **GitHub Actions** — `.github/workflows/android.yml`: main-এ push করলেই JDK 17 দিয়ে Debug + Release APK বিল্ড, artifact আপলোড

## বিল্ড

Android Studio-তে প্রজেক্ট খুলে Run করুন, অথবা:

```bash
./gradlew assembleDebug
```

নোট: এই প্রজেক্টে `gradlew` স্ক্রিপ্ট Android Studio প্রথমবার খুললে তৈরি হয় (gradle-wrapper.properties দেওয়া আছে, Gradle 8.2)। GitHub Actions-এ বিল্ডের আগে `gradlew` কমিট করা থাকতে হবে।
