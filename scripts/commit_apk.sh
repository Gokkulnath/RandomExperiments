#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# --- Configuration ---
APP_MODULE_DIR="SMSBackupToDrive"
APK_TARGET_DIR="apks" # Relative to project root

# --- Helper Functions ---
print_error() {
    echo "ERROR: $1" >&2
    exit 1
}

print_info() {
    echo "INFO: $1"
}

# --- Main Script ---

# 1. Navigate to the app module directory
print_info "Navigating to $APP_MODULE_DIR..."
if [ ! -d "$APP_MODULE_DIR" ]; then
    print_error "App module directory '$APP_MODULE_DIR' not found. Script should be run from project root."
fi
cd "$APP_MODULE_DIR"

# 2. Run the Gradle task to package the release APK
print_info "Building release APK using './gradlew :app:packageReleaseApk'..."
if ! ./gradlew :app:packageReleaseApk; then
    print_error "Gradle build failed."
fi
# The gradlew script prints the APK path, so we can rely on that if needed,
# or construct it as planned.

# 3. Navigate back to the project root directory
print_info "Navigating back to project root..."
cd ..

# 4. Retrieve the version name from build.gradle
print_info "Retrieving version name from $APP_MODULE_DIR/app/build.gradle..."
VERSION_NAME=$(grep "versionName \"" "$APP_MODULE_DIR/app/build.gradle" | awk -F'"' '{print $2}')

if [ -z "$VERSION_NAME" ]; then
    print_error "Could not retrieve versionName from $APP_MODULE_DIR/app/build.gradle."
fi
print_info "Version name found: $VERSION_NAME"

# 5. Construct the APK filename
APK_FILENAME="${APK_TARGET_DIR}/sms_backup_to_drive_v${VERSION_NAME}.apk"
print_info "Expected APK filename: $APK_FILENAME"

# 6. Check if the APK file exists
if [ ! -f "$APK_FILENAME" ]; then
    print_error "APK file '$APK_FILENAME' not found after build. Check Gradle task output."
fi
print_info "APK file found: $APK_FILENAME"

# 7. Stage the specific APK file
print_info "Staging APK file with git add..."
git add "$APK_FILENAME"

# 8. Create a commit message
CURRENT_UTC_DATETIME=$(date -u +"%Y-%m-%d %H:%M")
COMMIT_MESSAGE="feat: Build and add release APK v${VERSION_NAME} (${CURRENT_UTC_DATETIME} UTC)"
print_info "Using commit message: $COMMIT_MESSAGE"

# 9. Commit the staged APK
print_info "Committing APK..."
git commit -m "$COMMIT_MESSAGE"

# 10. Print a success message
print_info "APK built, added, and committed successfully!"
print_info "Run 'git push' to push the changes."

exit 0
