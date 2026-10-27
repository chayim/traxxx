.PHONY: help \
        server server-run server-seed \
        docker-build \
        android-wrapper android-build android-install android-dev android-logs android-stop \
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
# Requires: JDK 17, Android SDK (platform 34 + build-tools), ANDROID_HOME set.
# First build also downloads the Gradle distribution via the wrapper.

android-wrapper: ## Bootstrap the Gradle wrapper jar (needs a system `gradle` once)
	@if [ ! -f $(ANDROID_DIR)/gradle/wrapper/gradle-wrapper.jar ]; then \
	  cd $(ANDROID_DIR) && gradle wrapper --gradle-version 8.9 --distribution-type bin ; \
	fi

android-build: android-wrapper ## Assemble a debug APK (app/build/outputs/apk/debug/)
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

android-install: android-wrapper ## Build and install the debug APK on the connected device/emulator
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
