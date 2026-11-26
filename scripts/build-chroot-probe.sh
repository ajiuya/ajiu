#!/usr/bin/env bash
set -euo pipefail

project_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
kind="${1:-cgroup}"
[[ $# -le 1 ]] || { printf '%s\n' 'Expected an optional cgroup, mount, rootfs or guest probe kind.' >&2; exit 2; }
case "$kind" in
    cgroup) report_name=probe; source_name=cgroup_probe; binary_name=cgroup-probe ;;
    mount) report_name=mount-probe; source_name=mount_probe; binary_name=mount-probe ;;
    rootfs) report_name=rootfs-install; source_name=rootfs_install; binary_name=rootfs-install ;;
    guest) report_name=rootfs-probe; source_name=rootfs_probe; binary_name=rootfs-probe ;;
    *) printf '%s\n' 'Expected cgroup, mount, rootfs or guest probe kind.' >&2; exit 2 ;;
esac
source_file="$project_root/native/chroot/$source_name.c"
output_dir="$project_root/app/build/reports/chroot-runtime/$report_name"
compiler="${CC:-gcc}"

case "$("$compiler" -dumpmachine)" in
    aarch64*-linux-gnu*) ;;
    *) printf '%s\n' 'The probe requires the existing ARM64 Linux GCC toolchain.' >&2; exit 2 ;;
esac
[[ -f "$source_file" ]] || { printf '%s\n' 'The probe source file is not available.' >&2; exit 2; }
command -v readelf >/dev/null
mkdir -p "$output_dir"

flags=(-std=c11 -D_GNU_SOURCE -Os -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE -static-pie -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384)
"$compiler" "${flags[@]}" -c "$source_file" -o "$output_dir/$binary_name.o"
"$compiler" "${flags[@]}" "$output_dir/$binary_name.o" -o "$output_dir/$binary_name"
readelf -h -l -d "$output_dir/$binary_name" > "$output_dir/elf-inspection.txt"
python3 "$project_root/scripts/verify_chroot_probe.py" --kind "$kind"
if [[ "$kind" == cgroup ]]; then
    "$compiler" "${flags[@]}" "$project_root/native/chroot/cgroup_probe_test.c" -o "$output_dir/cgroup-probe-test"
    "$output_dir/cgroup-probe-test"
fi
"$compiler" "${flags[@]}" "$project_root/native/chroot/runtime_protocol.c" "$project_root/native/chroot/runtime_protocol_test.c" -o "$output_dir/runtime-protocol-test"
"$output_dir/runtime-protocol-test"
"$compiler" "${flags[@]}" "$project_root/native/chroot/runtime_protocol.c" "$project_root/native/chroot/runtime_protocol_io.c" "$project_root/native/chroot/runtime_protocol_io_test.c" -o "$output_dir/runtime-protocol-io-test"
"$output_dir/runtime-protocol-io-test"
{
    printf '%s\n' 'compiler:'
    "$compiler" --version
    printf '\nflags:'
    printf ' %q' "${flags[@]}"
    printf '\n'
} > "$output_dir/toolchain.txt"
mkdir -p "$output_dir/licenses"
cp /usr/share/doc/libc6-dev/copyright "$output_dir/licenses/glibc-copyright"
cp /usr/share/doc/libgcc-14-dev/copyright "$output_dir/licenses/gcc-runtime-copyright"
cp /usr/share/common-licenses/LGPL-2.1 "$output_dir/licenses/LGPL-2.1"
cp /usr/share/common-licenses/GPL-3 "$output_dir/licenses/GPL-3"
sha256sum "$source_file" "$output_dir/$binary_name.o" "$output_dir/$binary_name" > "$output_dir/sha256.txt"
printf '%s\n' "$output_dir/$binary_name"
