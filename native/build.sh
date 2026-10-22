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

# make, but when OpenSSH's portability code (DNS, pty, ... - not needed by sftp-server) fails to
# compile against old NDK headers, replace that source by an empty file and retry.
# If the stub was actually needed, the final link fails with an "undefined reference".
build_tolerant() {
  local out bad f n p diag
  for n in 1 2 3 4 5 6 7 8; do
    if out=$(make -k -j"$JOBS" "$@" 2>&1); then return 0; fi
    bad=$(printf '%s\n' "$out" | sed -n 's/.*\*\*\* \[Makefile:[0-9]*: \(.*\)\.o\] Error.*/\1/p' | sort -u)
    if [ -z "$bad" ]; then printf '%s\n' "$out" | tail -40; return 1; fi
    for f in $bad; do
      p=$(find . -name "$f.c" | head -1)
      # rebuild just this object serially to get a clean error message
      rm -f "${p%.c}.o"
      diag=$(cd "$(dirname "$p")" && make "$(basename "${p%.c}").o" 2>&1 | grep -E -B2 -A8 "error:" | head -50) || true
      case "$p" in
        ./dns.c|./openbsd-compat/*)
          case "$p" in ./openbsd-compat/arc4random.c|./openbsd-compat/explicit_bzero.c) f="" ;; esac ;;
        *) f="" ;;
      esac
      if [ -z "$f" ]; then
        echo "ERROR: required source does not compile: $p"
        printf '%s\n' "$diag"
        return 1
      fi
      echo "NOTE: stubbing source that does not compile on this NDK: $p"
      printf '%s\n' "$diag" | head -6
      : > "$p"
    done
  done
  printf '%s\n' "$out" | tail -40; return 1
}

mkdir -p "$WORK" "$OUT"
cd "$WORK"
[ -d dropbear-src ] || git clone -q --depth 1 --branch "$DROPBEAR_TAG" https://github.com/mkj/dropbear dropbear-src
[ -d openssh-src ]  || git clone -q --depth 1 --branch "$OPENSSH_TAG" https://github.com/openssh/openssh-portable openssh-src
git -C dropbear-src apply "$HERE/patches/dropbear-fssh.patch"
git -C openssh-src  apply "$HERE/patches/openssh-sftp.patch"
git -C openssh-src  apply "$HERE/patches/openssh-sftp-rename.patch"
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

  # ---- ls (toolbox ls lacks "." / ".." and the link-count column)
  "$CC" $COMMON_CFLAGS $COMMON_LDFLAGS -o "$WORK/ls-$ABI" "$HERE/ls.c"
  "$STRIP" -o "$OUT/$ABI/libls.so" "$WORK/ls-$ABI"

  # ---- sftp-server
  B=$WORK/openssh-$ABI; rm -rf "$B"; cp -r "$WORK/openssh-src" "$B"; cd "$B"
  # -D__sentinel__=__sentinel__: stop OpenSSH's defines.h from #defining it to nothing, which
  # breaks bionic's unistd.h (__attribute__((__sentinel__(1)))) when it is included afterwards
  # NDK r23 headers for old API levels: llabs() is a static inline and mblen() is declared
  # (API 26+) -> tell configure they exist so openbsd-compat does not redeclare/macro them.
  # mblen is only used by the sftp client, never linked into sftp-server.
  ac_cv_func_llabs=yes ac_cv_func_mblen=yes ac_cv_func_getpagesize=yes ac_cv_func_lldiv=yes \
  ac_cv_func_strtoll=yes ac_cv_func_strtoull=yes ac_cv_func_atoll=yes \
  ac_cv_func_bzero=yes ac_cv_func_bcopy=yes ac_cv_func_bcmp=yes ac_cv_func_getrrsetbyname=yes ac_cv_search_getrrsetbyname='none required' \
  ./configure --host="$HOSTT" CC="$CC" CFLAGS="$COMMON_CFLAGS -D__sentinel__=__sentinel__" LDFLAGS="$COMMON_LDFLAGS" \
      --without-openssl --without-zlib --without-pam --without-selinux --without-libedit \
      --without-hardening --disable-largefile --disable-strip --disable-security-key --disable-pkcs11 >/dev/null
  build_tolerant sftp-server
  "$STRIP" -o "$OUT/$ABI/libsftp.so" sftp-server
  ls -l "$OUT/$ABI"
  cd "$WORK"
done <<< "$TARGETS"
