#!/usr/bin/env bash
set -euo pipefail

package=false
if [[ $# == 1 && "$1" == --package ]]; then package=true
elif [[ $# != 0 ]]; then printf '%s\n' 'Expected no arguments or --package for APK source assets.' >&2; exit 2
fi
project_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"
compiler="${CC:-gcc}"
[[ "$("$compiler" -dumpmachine)" == aarch64*-linux-gnu* ]] || { printf '%s\n' 'ARM64 Linux GCC is required.' >&2; exit 2; }
output_dir="$project_root/app/build/reports/chroot-runtime/runtime"
objects="$output_dir/objects"
mkdir -p "$objects" "$output_dir/licenses"
flags=(-std=c11 -Os -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE -static-pie -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384)
for name in runtime_helper runtime_launch runtime_protocol runtime_protocol_io rootfs_install file_worker; do
    "$compiler" "${flags[@]}" -c "native/chroot/$name.c" -o "$objects/$name.o"
done
"$compiler" "${flags[@]}" "$objects/runtime_helper.o" "$objects/runtime_launch.o" "$objects/runtime_protocol.o" "$objects/runtime_protocol_io.o" -o "$output_dir/libajiu_chroot_runtime.so"
"$compiler" "${flags[@]}" "$objects/rootfs_install.o" -o "$output_dir/libajiu_chroot_installer.so"
"$compiler" "${flags[@]}" "$objects/file_worker.o" -o "$output_dir/libajiu_file_worker.so"
"$compiler" "${flags[@]}" -Wpedantic -c native/chroot/runtime_broker.c -o "$objects/runtime_broker.o"
"$compiler" "${flags[@]}" -Wpedantic "$objects/runtime_broker.o" -o "$output_dir/libajiu_chroot_broker.so"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fanalyzer \
    -c native/chroot/runtime_broker.c -o "$objects/runtime_broker-analyzer.o"
broker_test_flags=()
case "${AJIU_BROKER_TEST_FIXTURE_EXEC:-0}" in
    0) ;;
    1) broker_test_flags=(-DAJIU_BROKER_TEST_EXEC_FIXTURE) ;;
    *) printf '%s\n' 'AJIU_BROKER_TEST_FIXTURE_EXEC must be 0 or 1.' >&2; exit 2 ;;
esac
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
    "${broker_test_flags[@]}" native/chroot/runtime_broker_test.c -o "$output_dir/runtime-broker-test"
"$output_dir/runtime-broker-test"
"$compiler" "${flags[@]}" native/chroot/runtime_launch_test.c "$objects/runtime_launch.o" "$objects/runtime_protocol.o" -o "$output_dir/runtime-launch-test"
"$output_dir/runtime-launch-test"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
    native/chroot/runtime_diagnostics_test.c native/chroot/runtime_launch.c native/chroot/runtime_protocol.c \
    native/chroot/runtime_protocol_io.c -o "$output_dir/runtime-diagnostics-test"
"$output_dir/runtime-diagnostics-test"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
    native/chroot/runtime_namespace_test.c native/chroot/runtime_launch.c native/chroot/runtime_protocol.c \
    native/chroot/runtime_protocol_io.c -o "$output_dir/runtime-namespace-test"
"$output_dir/runtime-namespace-test"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
    native/chroot/runtime_guest_test.c native/chroot/runtime_launch.c native/chroot/runtime_protocol.c \
    native/chroot/runtime_protocol_io.c -o "$output_dir/runtime-guest-test"
"$output_dir/runtime-guest-test"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
    native/chroot/runtime_control_eof_test.c native/chroot/runtime_launch.c native/chroot/runtime_protocol.c \
    native/chroot/runtime_protocol_io.c -o "$output_dir/runtime-control-eof-test"
"$output_dir/runtime-control-eof-test"
for name in file_worker rootfs_install; do
    "$compiler" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fsanitize=undefined -fno-sanitize-recover=all \
        "native/chroot/${name}_test.c" -o "$output_dir/$name-test"
    python3 - "$output_dir/$name-test" "$name" <<'PY'
import os
from pathlib import Path
import subprocess
import sys
import tempfile
with tempfile.TemporaryDirectory(prefix='ajiu-chroot-offline-') as temporary:
    command = [str(Path(sys.argv[1]).resolve())]
    if sys.argv[2] == 'file_worker':
        command.append(temporary)
    environment = dict(os.environ, TMPDIR=temporary)
    subprocess.run(command, cwd=temporary, env=environment, check=True)
PY
done
python3 - "$project_root" <<'PY'
from pathlib import Path
import hashlib
import json
import sys
sys.path.insert(0, str(Path(sys.argv[1]) / 'scripts'))
from verify_chroot_probe import verify_elf
root = Path(sys.argv[1])
out = root / 'app/build/reports/chroot-runtime/runtime'
rows = []
for name in ('libajiu_chroot_runtime.so', 'libajiu_chroot_installer.so', 'libajiu_file_worker.so', 'libajiu_chroot_broker.so'):
    data = (out / name).read_bytes()
    verify_elf(data)
    rows.append({'name': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
(out / 'artifacts.json').write_text(json.dumps(rows, indent=2) + '\n')
print(json.dumps(rows, indent=2))
PY
cp /usr/share/doc/libc6-dev/copyright "$output_dir/licenses/glibc-copyright"
cp /usr/share/doc/libgcc-14-dev/copyright "$output_dir/licenses/gcc-runtime-copyright"
cp /usr/share/common-licenses/LGPL-2.1 "$output_dir/licenses/LGPL-2.1"
cp /usr/share/common-licenses/GPL-3 "$output_dir/licenses/GPL-3"
{
    "$compiler" --version
    printf '\nflags:'
    printf ' %q' "${flags[@]}"
    printf '\n'
} > "$output_dir/toolchain.txt"
sha256sum native/chroot/*.c native/chroot/*.h "$objects"/*.o "$output_dir"/libajiu*.so > "$output_dir/build-inputs.sha256"
if [[ "$package" == true ]]; then
    native_dir="$project_root/app/src/_armJniLibs/arm64-v8a"
    asset_dir="$project_root/app/src/_armAssets/chroot"
    mkdir -p "$native_dir" "$asset_dir/licenses"
    for mapping in 'libajiu_chroot_runtime.so:helper' 'libajiu_chroot_installer.so:installer' 'libajiu_file_worker.so:file-worker' 'libajiu_chroot_broker.so:broker'; do
        binary="${mapping%%:*}"
        manifest="${mapping#*:}"
        cp "$output_dir/$binary" "$native_dir/$binary"
        sha256sum "$native_dir/$binary" | cut -d ' ' -f 1 > "$asset_dir/$manifest.sha256"
    done
    cp "$output_dir/licenses/"* "$asset_dir/licenses/"
    cp "$output_dir/toolchain.txt" "$asset_dir/toolchain.txt"
    cp "$output_dir/artifacts.json" "$asset_dir/artifacts.json"
fi
printf '%s\n' 'Native artifacts were built and checked; nothing was installed or launched.'
