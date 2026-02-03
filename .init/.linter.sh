#!/bin/bash
cd /home/kavia/workspace/code-generation/drivesafe-auto-companion-211750-211759/android_frontend_app
./gradlew lint
LINT_EXIT_CODE=$?
if [ $LINT_EXIT_CODE -ne 0 ]; then
   exit 1
fi

