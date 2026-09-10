#!/bin/bash -e

BUILD="./buildscripts"

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf {app,.}/build app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

[ -n "$ANDROID_SIGNING_KEY" ] && BUNDLE=1
keypass=${ANDROID_SIGNING_ALIAS_PASSWORD:-$ANDROID_SIGNING_KEY_PASSWORD}

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo "$(realpath "$BUILD/prefix/$1")"
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

prefix32=$(nativeprefix "armv7l")
prefix64=$(nativeprefix "arm64")
prefix_x64=$(nativeprefix "x86_64")
prefix_x86=$(nativeprefix "x86")

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 1
fi

### Native parts
PREFIX32="$prefix32" PREFIX64="$prefix64" PREFIX_X64="$prefix_x64" PREFIX_X86="$prefix_x86" \
ndk-build -C app/src/main -j$cores

### Java parts
# Android's gradle plugin needs both of these to correctly strip libraries.
# We could pass them directly to Gradle but by using this file it will persist
# inside Android Studio too.
printf '%s\n' \
	"# This file is automatically written by the build scripts, and read using Gradle" \
	"ndkVersion=$v_ndk_n" "ndkRoot=$ANDROID_NDK_ROOT" >ndk.properties

targets=(assembleDebug)
if [ -z "$DONT_BUILD_RELEASE" ]; then
	targets+=(assembleRelease)
	[ -n "$BUNDLE" ] && targets+=(bundleRelease)
fi
./gradlew "${targets[@]}"

### Signing
if [ -n "$ANDROID_SIGNING_KEY" ]; then
	cd "app/build/outputs/apk"
	apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner
	ks_args=(--ks "${ANDROID_SIGNING_KEY}")
	[ -n "$ANDROID_SIGNING_ALIAS" ] && ks_args+=(--ks-key-alias "${ANDROID_SIGNING_ALIAS}")
	[ -n "$ANDROID_SIGNING_KEY_PASSWORD" ] && ks_args+=(--ks-pass "pass:${ANDROID_SIGNING_KEY_PASSWORD}")
	[ -n "$keypass" ] && ks_args+=(--key-pass "pass:${keypass}")
	for v in default allstorage; do
		pushd $v
		# sign only the universal debug APK
		"$apksigner" sign "${ks_args[@]}" \
			--in debug/app-$v-universal-debug.apk --out debug/app-$v-universal-debug-signed.apk
		# but all of the release APKs
		for apk in release/*-unsigned.apk; do
			"$apksigner" sign "${ks_args[@]}" \
				--in $apk --out ${apk/-unsigned/-signed}
		done
		popd
	done
	# and the bundle
	cd ../bundle
	if [ -n "$BUNDLE" ]; then
		if [ -z "$ANDROID_SIGNING_ALIAS" ]; then
			echo >&2 "Error: ANDROID_SIGNING_ALIAS must be set to use jarsigner"
			exit 1
		fi
		pushd defaultRelease
		jarsigner_args=(-keystore "${ANDROID_SIGNING_KEY}")
		[ -n "$ANDROID_SIGNING_KEY_PASSWORD" ] && jarsigner_args+=(-storepass "${ANDROID_SIGNING_KEY_PASSWORD}")
		[ -n "$keypass" ] && jarsigner_args+=(-keypass "${keypass}")
		jarsigner "${jarsigner_args[@]}" -signedjar \
			app-default-release-signed.aab app-default-release.aab \
			"${ANDROID_SIGNING_ALIAS}"
		popd
	fi
fi
