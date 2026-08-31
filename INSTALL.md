# Getting the app onto your phone without Android Studio

GitHub builds it for you. Their servers already have the Android tooling
installed, so you never install anything except the finished app.

---

## Step 1 — Put the code on GitHub

Create a new repository (private is fine — Actions works on private repos too),
then upload the contents of the `FaceID` folder to it.

Easiest route with no tools: on the repo page use **Add file → Upload files** and
drag the whole folder in. GitHub keeps the folder structure.

If you'd rather use the command line:

```bash
cd FaceID
git init
git add .
git commit -m "Face recognition app"
git branch -M main
git remote add origin https://github.com/<you>/who-is-there.git
git push -u origin main
```

Note the workflow triggers on `main` or `master`. If your default branch is
something else, either rename it or edit the branch list in
`.github/workflows/build.yml`.

---

## Step 2 — Wait about four minutes

The push starts the build automatically. Go to the **Actions** tab to watch it.

The workflow does three things: downloads the face model, compiles the app, and
publishes the result.

If it fails on the model download step, the log tells you exactly what to do —
that's the one part depending on a file hosted in someone else's repository, so
it's the one part that can rot.

---

## Step 3 — Install it on the phone

Open this on the phone itself:

```
https://github.com/<you>/who-is-there/releases/latest
```

Tap `who-is-there.apk`. Android will ask permission to install from your browser
— allow it, then confirm the install. The permission only needs granting once.

This is a debug-signed build, which is why Android asks. That's normal and fine
for an app you built for yourself.

There's also a zip under the Actions run's Artifacts section, but the release
link above is a direct APK with nothing to unzip, so use that.

---

## Step 4 — Add your people

The app reads one folder per person from:

```
Android/data/nl.bert.faceid/files/People/
```

The folder is created the first time the app runs, so **open the app once
before** trying to copy photos in.

Then plug the phone into a computer by USB, choose *File transfer* on the phone,
and copy folders in:

```
People/
  Anna/            <- folder name is what the app says out loud
    1.jpg
    2.jpg
    3.jpg
  Oma Trees/
    zomer.jpg
```

Open the app and tap **Reload people**. It says how many people it knows.

If USB is awkward, any file manager app on the phone can reach that path too.

---

## Making changes later

Edit a file on GitHub in the browser, commit, and a fresh APK appears at the
same release link a few minutes later. You never need a development machine.

---

## If you'd rather not use GitHub

Any of these work, in rough order of ease:

1. **Ask someone with Android Studio** to open the folder and press Run with
   your phone plugged in. About ten minutes for them, including the download of
   the model file described in `app/src/main/assets/README_PUT_MODEL_HERE.txt`.
2. **GitLab CI or Azure DevOps Pipelines** — same idea as the GitHub workflow.
   Azure's `ubuntu-latest` hosted agents also ship the Android SDK, so the
   pipeline is nearly a copy of `build.yml`: set up JDK 17, fetch the model,
   run `gradle assembleDebug`, publish the APK as a pipeline artifact.
3. Install Android Studio yourself. It's a big download, but it's also the
   easiest way to see error messages if something misbehaves on your specific
   phone.
