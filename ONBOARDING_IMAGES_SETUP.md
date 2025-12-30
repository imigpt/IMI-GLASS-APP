# Onboarding Images Setup

## Image Placement Instructions

To use your custom glass product images in the onboarding screens:

### Step 1: Rename Your Images
Rename the 3 images you want to use as follows:
- First image (glasses with blue arrow on temple) → `onboarding1.png`
- Second image (glasses top view with blue touch button) → `onboarding2.png`
- Third image (glasses with dark lenses) → `onboarding3.png`

### Step 2: Copy to Assets Folder
Place all 3 images into:
```
app/src/main/assets/
```

Your folder structure should look like:
```
app/src/main/assets/
  ├── onboarding1.png
  ├── onboarding2.png
  └── onboarding3.png
```

### Step 3: Rebuild
After placing the images, rebuild the app:
```bash
./gradlew clean assembleDebug
```

## Image Requirements
- **Format:** PNG (preferred) or JPG
- **Recommended size:** 1080x1920px or similar high resolution
- **Aspect ratio:** Portrait or square works best
- **Background:** Transparent or white background recommended

## What Changed
The app now loads onboarding images from the `assets` folder instead of using vector drawables. This allows you to use actual product photos for a more professional look.

## Screens
1. **Slide 1:** "Welcome to IMI Glasses AI" - Shows glasses with gesture indicator
2. **Slide 2:** "Find Things Easily" - Shows glasses with button/touch area highlighted
3. **Slide 3:** "Hands-Free AI Help" - Shows glasses with voice command feature

## Testing
Once images are in place, uninstall the app and reinstall to see the onboarding flow with your custom images.
