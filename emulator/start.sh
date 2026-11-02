#!/bin/bash
# Local Firebase (auth, firestore, storage) for testing the Android app with FAKE accounts.
# Project id demo-fariin: the demo- prefix means nothing here can reach the real project.
# The rules are copied fresh from the iOS repo each start, so tests run against the live rules.
cd "$(dirname "$0")"
cp ../../Kulan-iOS/firestore.rules firestore.rules
cp ../../Kulan-iOS/storage.rules storage.rules
# The ONE local change to the copy: the Storage emulator hands out http:// download URLs, and the
# live rules take only https media addresses (mediaUrlOk), so every photo message was refused here.
# Only this local copy is touched; the iOS repo's file stays as deployed.
sed -i "s#'(https|sticker)://.+'#'(https?|sticker)://.+'#" firestore.rules
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
exec npx -y firebase-tools@13 emulators:start --project demo-fariin --only auth,firestore,storage
