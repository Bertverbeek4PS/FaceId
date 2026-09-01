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

## Using the Ray-Ban Meta glasses camera

The app can use your Meta glasses camera instead of the phone camera. Tap the
**Camera: Phone / Camera: Glasses** button to switch. The phone camera stays as a
fallback, so nothing breaks if the glasses are off or out of range.

This uses Meta's Wearables Device Access Toolkit, which is a *developer preview*.
That has three consequences worth knowing up front:

- You can build and test on your own phone, but you cannot publish to the Play
  Store yet.
- The app now needs **Android 10 (or newer)** — the SDK's minimum.
- Downloading the SDK needs a GitHub token (below). Without it the build fails
  with an authentication error.

### One-time setup on the glasses

1. In the **Meta AI app**, pair and connect your glasses as normal.
2. Enable **Developer Mode**: Settings → App Info → tap the App version five
   times → toggle Developer Mode on.
3. Make sure the Meta AI app and glasses firmware are current (the toolkit
   needs recent versions).

### The GitHub token for downloading the SDK

The SDK is served from GitHub Packages, which always requires a token — even
though the packages are public.

1. On GitHub, go to **Settings → Developer settings → Personal access tokens →
   Tokens (classic)** and create a token with **only** the `read:packages`
   scope.
2. For CI builds (the GitHub Actions route above), add the token as a repository
   secret named **`MWDAT_GITHUB_TOKEN`** under
   **Settings → Secrets and variables → Actions**. The build workflow already
   reads it. The built-in Actions token cannot read another organisation's
   packages, which is why a separate secret is needed.
3. For local builds (Android Studio or `gradle` on your machine), create a file
   named `local.properties` in the project root with:

   ```properties
   github_token=ghp_your_token_here
   ```

   This file is already git-ignored, so the token never gets committed.

### First run with the glasses

The first time you tap **Camera: Glasses**, the app asks the Meta AI app to
register it and to grant camera access. Both prompts open the Meta AI app and
return to this app automatically. After that, switching cameras is instant.

If registration or the connection times out, the app says so and quietly falls
back to the phone camera — just try again once the glasses are awake and nearby.

### Using a real Application ID (Wearables Developer Center)

Developer Mode (the steps above) is enough to test on your own phone with the
`"0"` placeholders. You only need a real Application ID + Client Token when you
want an attested build or to share the app with other testers. Here's the full
path:

1. **Organisation.** Your company should have exactly one Managed Meta Account
   (MMA) organisation. Check with whoever manages it before creating a new one.
   Sign in to the [Wearables Developer Center](https://wearables.developer.meta.com/);
   first sign-in walks you through the MMA setup.
2. **Create a project.** Dashboard → **New project** → give it a name and short
   description.
3. **Add the Android app.** In the project, open **Configuration** and add a
   mobile app. Use this app's package name exactly: `nl.bert.faceid`.
   (iOS and Android must be separate apps if you ever add iOS.)
4. **Copy the credentials.** The Configuration page shows the **Application ID**
   and **Client Token** for the Android app. These are what replace the `"0"`
   placeholders.
5. **Justify the camera permission.** Open the **Permissions** tab and write a
   short justification for camera access (internal review only, not shown to
   users).
6. **Put the values in `local.properties`** (already git-ignored):

   ```properties
   github_token=ghp_your_token_here
   mwdat_application_id=YOUR_APPLICATION_ID
   mwdat_client_token=YOUR_CLIENT_TOKEN
   ```

   The build reads these automatically; leave them out and it stays on `"0"`
   (Developer Mode). For CI builds, add them as the repository secrets
   `MWDAT_APPLICATION_ID` and `MWDAT_CLIENT_TOKEN` and pass them into the build
   step the same way the SDK token is passed.
7. **Share with testers (optional).** Under **Distribute**, create a version,
   wait for its build status to reach **Ready**, then create a **release
   channel** and invite testers by their Meta account email. Testers still need
   Developer Mode off but must accept the channel invite; you keep Developer Mode
   on only for your own local builds.

Two gotchas: in Developer Mode only **one** third-party app can be registered at
a time, and the package name in the Developer Center must match `nl.bert.faceid`
or attestation will refuse the connection.

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
