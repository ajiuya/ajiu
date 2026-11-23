#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'gradle-mobile: %s\n' "$1" >&2
    exit 2
}

check_property() {
    local name="${1%%=*}"
    name="${name#org.gradle.project.}"
    case "$name" in
        org.gradle.jvmargs|org.gradle.workers.max|org.gradle.parallel|org.gradle.daemon|org.gradle.configuration-cache|kotlin.compiler.execution.strategy|mobileBuild|mobileTestHeap)
            fail "手机档属性由脚本管理；堆大小请使用 MOBILE_GRADLE_HEAP / MOBILE_TEST_HEAP。"
            ;;
    esac
}

gradle_heap="${MOBILE_GRADLE_HEAP-3g}"
test_heap="${MOBILE_TEST_HEAP-1g}"
[[ "$gradle_heap" =~ ^[1-9][0-9]*[mMgG]$ ]] || fail "MOBILE_GRADLE_HEAP 必须为正整数加 m 或 g，例如 3072m、3g。"
[[ "$test_heap" =~ ^[1-9][0-9]*[mMgG]$ ]] || fail "MOBILE_TEST_HEAP 必须为正整数加 m 或 g，例如 1024m、1g。"

expect_property=false
for arg in "$@"; do
    if "$expect_property"; then
        [[ -n "$arg" && "$arg" != -* ]] || fail "Gradle 属性选项缺少有效值。"
        check_property "$arg"
        expect_property=false
        continue
    fi
    case "$arg" in
        --|--daemon|--daemon=*|--foreground|--foreground=*|--stop|--stop=*|--parallel|--parallel=*|--configuration-cache|--configuration-cache=*|--max-workers|--max-workers=*)
            fail "参数与手机档约束冲突；不支持启用 daemon、并行、配置缓存、覆盖 worker 或停止其他 daemon。"
            ;;
        -p|-p?*|--project-dir|--project-dir=*|-b|-b?*|--build-file|--build-file=*|-c|-c?*|--settings-file|--settings-file=*)
            fail "手机档仅用于脚本所在项目，不支持重定向项目或构建配置。"
            ;;
        -D|-P|--system-prop|--project-prop)
            expect_property=true
            ;;
        -D?*|-P?*)
            check_property "${arg:2}"
            ;;
        --system-prop=*|--project-prop=*)
            check_property "${arg#*=}"
            ;;
        --*)
            option_name="${arg%%=*}"
            for protected_option in --daemon --foreground --stop --parallel --configuration-cache --max-workers --project-dir --build-file --settings-file --system-prop --project-prop; do
                if [[ "$protected_option" == "$option_name"* ]]; then
                    fail "手机档不支持受保护选项的缩写，请使用完整 Gradle 选项。"
                fi
            done
            ;;
    esac
done
if "$expect_property"; then
    fail "Gradle 属性选项缺少有效值。"
fi

project_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$project_root"
exec "$project_root/gradlew" "$@" \
    "-Dorg.gradle.jvmargs=-Xmx${gradle_heap} -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC" \
    --max-workers=1 \
    --no-parallel \
    --no-daemon \
    --no-configuration-cache \
    -Pkotlin.compiler.execution.strategy=in-process \
    -PmobileBuild=true \
    "-PmobileTestHeap=${test_heap}"
