#!/usr/bin/env bash
# Cross-builds dropbear (+scp) and OpenSSH sftp-server for Android.
# Usage: native/build.sh <NDK_DIR> <OUT_JNILIBS_DIR>
# Output: <OUT>/<abi>/libdropbear.so, libscp.so, libsftp.so  (they are plain
# PIE executables; the ".so" name makes Android install them into nativeLibraryDir)
set -euo pipefail

NDK=$(realpath "$1")
OUT=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
DROPBEAR_TAG=${DROPBEAR_TAG:-DROPBEAR_2026.94}
OPENSSH_TAG=${OPENSSH_TAG:-V_10_6_P1}
WORK=${WORK:-$HERE/../build-native}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
JOBS=$(nproc)

mkdir -p "$WORK" "$OUT"
cd "$WORK"
[ -d dropbear-src ] || git clone -q --depth 1 --branch "$DROPBEAR_TAG" https://github.com/mkj/dropbear dropbear-src
[ -d openssh-src ]  || git clone -q --depth 1 --branch "$OPENSSH_TAG" https://github.com/openssh/openssh-portable openssh-src
git -C dropbear-src apply "$HERE/patches/dropbear-fssh.patch"
git -C openssh-src  apply "$HERE/patches/openssh-sftp.patch"
(cd openssh-src && autoreconf -i >/dev/null)

# abi : clang target : api
TARGETS="armeabi-v7a:armv7a-linux-androideabi:17
arm64-v8a:aarch64-linux-android:21
x86:i686-linux-android:17
x86_64:x86_64-linux-android:21"

COMMON_CFLAGS="-Os -fPIE -ffunction-sections -fdata-sections -fvisibility=hidden"
COMMON_LDFLAGS="-pie -Wl,--gc-sections -Wl,-z,relro,-z,now"

while IFS=: read -r ABI TRIPLE API; do
  echo "=== $ABI (API $API)"
  CC="$TC/bin/${TRIPLE}${API}-clang"
  [ -x "$CC" ] || { echo "missing $CC"; exit 1; }
  HOSTT=${TRIPLE/armv7a/arm}
  export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip

  # ---- dropbear + scp
  B=$WORK/dropbear-$ABI; rm -rf "$B"; cp -r "$WORK/dropbear-src" "$B"; cd "$B"
  cp "$HERE/localoptions.h" .
  ./configure --host="$HOSTT" CC="$CC" CFLAGS="$COMMON_CFLAGS -DFSSH_PTY" LDFLAGS="$COMMON_LDFLAGS" \
      --disable-syslog --disable-lastlog --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx \
      --disable-pututline --disable-pututxline --disable-zlib --disable-largefile \
      LTM_CFLAGS="-Os" >/dev/null
  make -j"$JOBS" PROGRAMS="dropbear scp" >/dev/null
  mkdir -p "$OUT/$ABI"
  "$STRIP" -o "$OUT/$ABI/libdropbear.so" dropbear
  "$STRIP" -o "$OUT/$ABI/libscp.so" scp

  # ---- sftp-server
  B=$WORK/openssh-$ABI; rm -rf "$B"; cp -r "$WORK/openssh-src" "$B"; cd "$B"
  # NDK r23 headers for old API levels: llabs() is a static inline and mblen() is declared
  # (API 26+) -> tell configure they exist so openbsd-compat does not redeclare/macro them.
  # mblen is only used by the sftp client, never linked into sftp-server.
  ac_cv_func_llabs=yes ac_cv_func_mblen=yes ac_cv_func_getpagesize=yes ac_cv_func_lldiv=yes \
  ac_cv_func_strtoll=yes ac_cv_func_strtoull=yes ac_cv_func_atoll=yes \
  ac_cv_func_bzero=yes ac_cv_func_bcopy=yes ac_cv_func_bcmp=yes \
  ./configure --host="$HOSTT" CC="$CC" CFLAGS="$COMMON_CFLAGS" LDFLAGS="$COMMON_LDFLAGS" \
      --without-openssl --without-zlib --without-pam --without-selinux --without-libedit \
      --without-hardening --disable-largefile --disable-strip --disable-security-key --disable-pkcs11 >/dev/null
  make -j"$JOBS" sftp-server >/dev/null
  "$STRIP" -o "$OUT/$ABI/libsftp.so" sftp-server
  ls -l "$OUT/$ABI"
  cd "$WORK"
done <<< "$TARGETS"
