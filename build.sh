#!/bin/bash
set -e

BL=$PWD/treble_aosp
BD=$PWD/duo-de/builds
BV=$1


echo "--> Setting up build environment"
source build/envsetup.sh &>/dev/null
mkdir -p $BD
lunch treble_arm64_bgN-ap2a-userdebug
make PostureProcessor
# adb install --staged out/target/product/tdgsi_arm64_ab/system/system_ext/priv-app/PostureProcessor/PostureProcessor.apk
# adb reboot



# Set variables
REPO="Archfx/duoPosture" 
TAG_NAME="v$(date +'%Y%m%d%H%M%S')"  
APK_PATH="out/target/product/tdgsi_arm64_ab/system/system_ext/priv-app/PostureProcessor/PostureProcessor.apk" 
RELEASE_NAME="PostureProcessor Release $TAG_NAME"
RELEASE_BODY="Dubug"

SIGNED_APK_PATH="duo-de/apk/PostureProcessor.apk"
KEY="../archfx-priv/keys/releasekey.pk8"
CERT="../archfx-priv/keys/releasekey.x509.pem"

apksigner sign --key $KEY --cert $CERT  $APK_PATH 
apksigner verify $APK_PATH 

# Check if build was successful
if [ ! -f "$APK_PATH" ]; then
  echo "Error: APK not found at $APK_PATH"
  exit 1
fi

echo "Creating GitHub release $RELEASE_NAME..."
gh release create "$TAG_NAME" "$APK_PATH" --repo "$REPO" --title "$RELEASE_NAME" --notes "$RELEASE_BODY"

echo "Release $RELEASE_NAME created successfully!"
