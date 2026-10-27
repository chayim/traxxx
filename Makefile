.PHONY: help \
        server server-run server-seed \
        docker-build \
        android-sdk android-wrapper apk android-install android-dev android-logs android-stop \
        clean

SERVER_DIR  := server
ANDROID_DIR := android

API_ADDR ?= :8080
DB       ?= traxxx.db

APP_ID   := com.kirshen.traxxx

help:  ## Show this help
	@echo "traxxx build targets"
	@echo
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_.-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo
	@echo "Variables: API_ADDR=$(API_ADDR)  DB=$(DB)"

# ---------- server (Go) ----------

server: ## Build the Go server binary (server/server)
	cd $(SERVER_DIR) && go mod tidy && go build -o traxxx .

server-run: ## Run the Go server (API_ADDR, DB)
	cd $(SERVER_DIR) && go run . --addr $(API_ADDR) --db $(DB)

server-seed: ## Wipe and seed the DB with ~100 fake activities
	cd $(SERVER_DIR) && go run . --seed --db $(DB)

# ---------- docker (server image) ----------
# Override DOCKER_IMAGE to your Docker Hub repo, e.g. DOCKER_IMAGE=youruser/traxxx-server
DOCKER_IMAGE ?= traxxx-server
DOCKER_TAG   ?= latest
DATA_VOLUME  ?= traxxx-data

docker-build: ## Build the server Docker image
	docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) $(SERVER_DIR)

# ---------- android (native Kotlin / Jetpack) ----------
# Requires: JDK 17. The `android-sdk` target bootstraps a user-local Android SDK
# (cmdline-tools + platform-tools + platforms;android-34 + build-tools;34.0.0)
# into $(ANDROID_SDK_ROOT) and accepts the licenses. The first build also
# downloads the Gradle distribution via the wrapper.

# Where to put the user-local Android SDK. Override with
# `make apk TRAXXX_ANDROID_SDK=/some/path`. We deliberately do NOT
# honor ANDROID_SDK_ROOT / ANDROID_HOME from the environment here, because a
# common setup points those at a read-only system install (e.g. /opt/android-sdk)
# that is missing platforms/build-tools and cannot be updated.
TRAXXX_ANDROID_SDK  ?= $(HOME)/.android-sdk
ANDROID_PLATFORM    := android-34
ANDROID_BUILD_TOOLS := 34.0.0
CMDLINE_TOOLS_ZIP   := commandlinetools-linux-11076708_latest.zip
CMDLINE_TOOLS_URL   := https://dl.google.com/android/repository/$(CMDLINE_TOOLS_ZIP)

# Force Gradle and the wrapper to use our user-local SDK.
export ANDROID_SDK_ROOT = $(TRAXXX_ANDROID_SDK)
export ANDROID_HOME     = $(TRAXXX_ANDROID_SDK)

android-sdk: ## Install a user-local Android SDK (cmdline-tools, platform 34, build-tools 34)
	@set -e; \
	SDKMANAGER="$(TRAXXX_ANDROID_SDK)/cmdline-tools/latest/bin/sdkmanager"; \
	if [ ! -x "$$SDKMANAGER" ]; then \
	  echo ">> Installing Android cmdline-tools into $(TRAXXX_ANDROID_SDK)"; \
	  mkdir -p "$(TRAXXX_ANDROID_SDK)/cmdline-tools"; \
	  TMP=$$(mktemp -d); \
	  curl -fsSL "$(CMDLINE_TOOLS_URL)" -o "$$TMP/tools.zip"; \
	  unzip -q "$$TMP/tools.zip" -d "$$TMP"; \
	  rm -rf "$(TRAXXX_ANDROID_SDK)/cmdline-tools/latest"; \
	  mv "$$TMP/cmdline-tools" "$(TRAXXX_ANDROID_SDK)/cmdline-tools/latest"; \
	  rm -rf "$$TMP"; \
	fi; \
	yes | "$$SDKMANAGER" --sdk_root="$(TRAXXX_ANDROID_SDK)" --licenses >/dev/null; \
	"$$SDKMANAGER" --sdk_root="$(TRAXXX_ANDROID_SDK)" \
	  "platform-tools" \
	  "platforms;$(ANDROID_PLATFORM)" \
	  "build-tools;$(ANDROID_BUILD_TOOLS)"

android-wrapper: ## Bootstrap the Gradle wrapper jar (needs a system `gradle` once)
	@if [ ! -f $(ANDROID_DIR)/gradle/wrapper/gradle-wrapper.jar ]; then \
	  cd $(ANDROID_DIR) && gradle wrapper --gradle-version 8.9 --distribution-type bin ; \
	fi
	@chmod +x $(ANDROID_DIR)/gradlew

apk: android-sdk android-wrapper ## Assemble a debug APK (app/build/outputs/apk/debug/)
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

android-install: android-sdk android-wrapper ## Build and install the debug APK on the connected device/emulator
	cd $(ANDROID_DIR) && ./gradlew installDebug

android-dev: android-install ## Install and launch the app on the connected device/emulator
	adb shell am start -n $(APP_ID)/.MainActivity

android-logs: ## Follow app logs (service + sync + webview)
	adb logcat -s TraxxxService:V TraxxxSync:V TraxxxBoot:V WorkManager:I AndroidRuntime:E System.err:W

android-stop: ## Force-stop the app (useful for testing survive-kill)
	adb shell am force-stop $(APP_ID)

# ---------- cleanup ----------

clean: ## Remove build artifacts (keeps node_modules, keeps DB)
	rm -f $(SERVER_DIR)/server
	rm -rf $(ANDROID_DIR)/app/build $(ANDROID_DIR)/build $(ANDROID_DIR)/.gradle
