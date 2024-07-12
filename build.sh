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
