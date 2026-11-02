#!/bin/bash
# Local Firebase (auth, firestore, storage) for testing the Android app with FAKE accounts.
# Project id demo-fariin: the demo- prefix means nothing here can reach the real project.
# The rules are copied fresh from the iOS repo each start, so tests run against the live rules.
cd "$(dirname "$0")"
cp ../../Kulan-iOS/firestore.rules firestore.rules
cp ../../Kulan-iOS/storage.rules storage.rules
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
exec npx -y firebase-tools@13 emulators:start --project demo-fariin --only auth,firestore,storage
