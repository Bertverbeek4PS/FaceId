# Who Is There — offline face recognition for Android

Recognises people from a folder of photos you provide, and says their name out
loud. Everything runs on the phone: no account, no internet, no cloud, no API
keys, no subscription. Face data never leaves the device.

Built to be usable without looking at the screen.

---

## Setup, once

### 1. Add the model file

Download `facenet.tflite` and put it in `app/src/main/assets/`.

From: `github.com/shubham0204/FaceRecognition_With_FaceNet_Android`
→ `app/src/main/assets/facenet.tflite`

See `app/src/main/assets/README_PUT_MODEL_HERE.txt` for alternatives. The code
reads the input and output size from the model, so any FaceNet or MobileFaceNet
variant drops in without code changes.

### 2. Build and install

Open the folder in Android Studio, let it sync, press Run. Android Studio will
generate the Gradle wrapper on first sync.

From the command line, once the wrapper exists:

```
./gradlew installDebug
```

### 3. Add people

The app reads one folder per person from:

```
Android/data/nl.bert.faceid/files/People/
```

Layout:

```
People/
  Anna/
    1.jpg
    2.jpg
    3.jpg
  Oma Trees/
    trees_zomer.jpg
  Jan van Dijk/
    jan1.jpg
    jan2.jpg
```

**The folder name is what the app says out loud.** Spell it how you want to hear
it.

That location needs no storage permission and shows up over USB and in any file
manager, so someone can drop photos in for you without going through the app.

By adb:

```
adb push ./Anna /sdcard/Android/data/nl.bert.faceid/files/People/
```

Then tap **Reload people**.

---

## Photo tips

This is where accuracy is won or lost.

- **3 to 5 photos per person.** More helps, but with diminishing returns after
  about five.
- **Vary them.** Different day, different light, glasses on and off, with and
  without a beard, indoors and outdoors. Five near-identical photos are worth
  about as much as one.
- **Face reasonably front-on and reasonably large** in the frame. A face 40
  pixels wide in a holiday group shot will not work.
- **One face per photo, or the biggest face wins.** The app takes the largest
  face it finds, so crop group photos first.
- Photos with no detectable face are skipped silently. If someone is never
  recognised, that is the first thing to check.

---

## Using it

| Control | What it does |
|---|---|
| **Start looking** | Begins scanning. Names are spoken as they are recognised |
| **Add person** | Takes 3 photos, then asks for the name by voice |
| **Manage people** | Lists everyone; tap a name to remove them and their photos |
| **Reload people** | Rescans the folders after adding photos over USB |
| **Sensitivity** | Cycles relaxed → normal → strict → very strict, spoken aloud |
| Big text area | Tap to repeat the last thing said. **Press and hold to change screen brightness** |

### Brightness

The screen starts at 2% backlight, and there is no camera preview and no solid
block of colour anywhere — only thin amber strokes on black. Press and hold the
big text area to step through very dark → dark → medium → bright → follow the
phone's own setting. Each step is spoken, and the choice is remembered.

If it is still too bright at the lowest step, Android's own *Extra dim*
(Settings → Accessibility → Display) stacks on top of this.

### Adding someone from inside the app

1. Tap **Add person**.
2. Point the camera at their face. It says "Hold still", then counts:
   "Photo 1 of 3", "Photo 2 of 3", "Photo 3 of 3", roughly one per second.
   Move slightly between them — a small change of angle is worth more than
   three identical shots.
3. The phone asks who it is. Say the name out loud.
4. It reads the name back: "Save as Anna?" Confirm, or say it again, or type it.

The photos land in that person's folder like any other, so app-added and
USB-added people work identically. Adding the same person twice adds to their
folder rather than replacing it — that is how you fix a person who is being
missed: enrol them again in different light.

Sounds and buzzes:

- Two short buzzes — someone was recognised
- One long buzz — a face is there, but not one it knows
- Spoken aiming help — "Move closer", "Face to your left"

The aiming help matters more than it sounds. The camera is not where your eyes
are, so the app tells you what the camera can actually see.

A name is not repeated within 9 seconds, so standing in front of someone does
not produce a loop.

---

## Tuning it

The app only says a name when two things are true: the best match beats the
threshold, **and** it beats the second-best person by a clear margin. That
second rule prevents the worst failure — confidently saying the wrong name for
two people who look somewhat alike.

The small grey line under the big text shows the live score and the runner-up.
Use it while you calibrate:

- **Never recognises people you added** → Sensitivity to relaxed. If scores are
  stuck around 0.3, the enrolment photos are the problem, not the threshold.
- **Says the wrong name** → Sensitivity to strict or very strict, and add more
  photos of both people involved.
- **Says "I don't know this person" for someone you enrolled, while their score
  reads 0.58 and the runner-up reads 0.56** → the margin rule is firing. Add
  more photos of both.

Expect this to work well front-on in reasonable light, and to fail in profile,
in the dark, or behind a hat and sunglasses. That is the state of the art at
5 MB, not a bug in the code.

---

## Project layout

```
MainActivity.kt   camera loop, spoken UI, aiming help, cooldowns
FaceFinder.kt     ML Kit face detection + crop helpers
FaceEmbedder.kt   TFLite: face crop -> vector
Matcher.kt        cosine similarity + runner-up margin rule
PeopleStore.kt    folder scan, EXIF handling, embedding cache
Speaker.kt        text-to-speech routed to the glasses + haptics
GlassesCamera.kt  Phase 2: where the Ray-Ban Meta camera plugs in
```

Embeddings are cached in internal storage, keyed by file size and modification
time, so adding one photo and reloading only processes that one photo.

---

## Phase 2: the glasses camera

Right now this uses the phone camera. The pipeline takes a `Bitmap` and does not
care where it came from, so switching to the Ray-Ban Meta camera means
implementing one interface. `GlassesCamera.kt` has the interface, the setup
steps, and notes on what to change in `MainActivity`.

Short version: enable Developer Mode on the glasses in the Meta AI app, add
`github.com/facebook/meta-wearables-dat-android`, capture stills rather than
video, hand each frame to `process()`. Meta publishes AI-ready docs and coding
skills for that SDK — use them rather than guessing method names, since the
toolkit is in developer preview and moves between versions.

Developer Preview allows building and testing but not publishing. Installing
your own build on your own phone is testing, so this is fine.

---

## Privacy

Face embeddings are biometric data under GDPR. The design keeps you on solid
ground: vectors stay on the device, nothing is uploaded, and deleting a person's
folder plus tapping Reload removes them completely.

Ask people before you add them. Not because a law forces you to in a purely
personal setting, but because it is the thing that makes this tool welcome
instead of creepy — and it is also what makes the recognition good, since a
person who agreed will happily let you take three decent photos.

I'm not a lawyer, and none of this is legal advice.
