# Install Metra with SponsorBlock for TikTok

You need an Android phone (Android 8 or newer), about 1 GB of free space and 10 minutes.

## 1. Install Morphe Manager

Download the latest `.apk` from [Morphe Manager releases](https://github.com/MorpheApp/morphe-manager/releases) and install it. Version 2.0.0 or newer is required. Android asks to allow installs from your browser; allow it.

## 2. Add this patch source

On the phone, open this link and confirm in Morphe:

**[Add Metra with SponsorBlock for TikTok](https://morphe.software/add-source?github=MidTano%2Fmetra-tiktok-sponsorblock)**

If the link does not open Morphe: in Morphe go to the patch sources, tap **+**, choose **Remote** and paste `https://github.com/MidTano/metra-tiktok-sponsorblock`.

## 3. Remove the official TikTok

The patched app is signed differently, so Android cannot install it over the Play Store TikTok. Uninstall TikTok first. Your account stays on TikTok's servers; you only sign in again.

## 4. Patch TikTok

1. In Morphe, tap **TikTok**. If it asks which source to use, pick **Metra with SponsorBlock for TikTok**.
2. Morphe shows the TikTok version it needs (currently **47.4.3**). Tap **Yes, help me find an APK** and follow it to APKMirror.
3. Download exactly that version (an `.apk`, not a newer one). **Do not install it.** Go back to Morphe.
4. Tap **Open APK file** and pick the downloaded file from **Downloads**.
5. Wait a few minutes for patching. Keep Morphe open if your phone tends to stop apps in the background.
6. Tap **Install**, allow the install when Android asks, then **Open**.

Ad skipping is in the default patch selection, so Simple mode is enough.

## 5. Use it

- Sign in to TikTok and scroll. Ads other people marked are skipped on their own and shown in green on the progress bar.
- To mark a new ad: open the video's share sheet, tap **Mark ad**, set where the ad starts and ends, and save. It is skipped in every video where that sound plays, for you and, with the shared base on, for everyone.
- Settings: Metra settings > Feed filter > Skip ads by sound.

## Updates

Morphe tells you when a new patch version is out. Tap **Update** on the source, then patch TikTok again the same way; your data stays.

## Problems

- **"App not installed"**: the official TikTok is still on the phone. Uninstall it and install again.
- **The file cannot be picked**: move the APK from Downloads to the root of internal storage and try again.
- **Patching stops halfway**: allow Morphe to run without battery restrictions (Morphe Settings > Patcher > Battery optimization).

Report bugs in [Issues](https://github.com/MidTano/metra-tiktok-sponsorblock/issues) with the patching log.
