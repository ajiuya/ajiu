#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdarg.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static int home_test_fstat(int fd, struct stat *st);
static int home_test_mkdirat(int directory, const char *name, mode_t mode);
static int home_test_fchown(int fd, uid_t uid, gid_t gid);
static int home_test_fchmod(int fd, mode_t mode);
static long home_test_syscall(long number, ...);
#define main chroot_runtime_production_main_not_called
#define fstat home_test_fstat
#define mkdirat home_test_mkdirat
#define fchown home_test_fchown
#define fchmod home_test_fchmod
#define syscall home_test_syscall
#include "runtime_helper.c"
#undef syscall
#undef fchmod
#undef fchown
#undef mkdirat
#undef fstat
#undef main

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Home target check line %d: %s (errno=%d)\n", __LINE__, #value, errno); exit(1); } ++checks; } while (0)
#define APP_UID 10123U
#define APP_GID 10123U
static unsigned int checks, cases;
struct metadata {
    dev_t device;
    ino_t inode;
    uid_t uid;
    gid_t gid;
    bool different_device, different_mount, missing_mount;
    int stat_error;
};
static struct metadata metadata[16];
static size_t metadata_count;
enum race { NO_RACE, CONFIG_SWAP, HOME_SWAP, LEAF_SWAP, LEAF_DIRECTORY_SWAP, COMPETING_APP, COMPETING_FOREIGN };
static struct home_fixture {
    char path[64];
    int root, home, config, outside;
    size_t home_metadata, config_metadata, output_metadata, created_metadata;
    enum race race;
    unsigned int mkdirs, chowns, chmods, swaps;
    const char *open_failure;
    int open_error, mkdir_error, chown_error, chmod_error, created_fault;
    bool chown_unchanged, chmod_wrong;
} fixture;

static size_t remember(int directory, const char *name, uid_t uid, gid_t gid)
{
    struct stat st;
    CHECK(fstatat(directory, name, &st, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(metadata_count < sizeof(metadata) / sizeof(*metadata));
    size_t index = metadata_count++;
    metadata[index] = (struct metadata){ .device = st.st_dev, .inode = st.st_ino, .uid = uid, .gid = gid };
    return index;
}

static struct metadata *metadata_for(int fd)
{
    struct stat st;
    CHECK(fstat(fd, &st) == 0);
    for (size_t i = 0; i < metadata_count; ++i)
        if (metadata[i].device == st.st_dev && metadata[i].inode == st.st_ino) return &metadata[i];
    fprintf(stderr, "Untracked fixture inode: case=%u race=%d fd=%d mode=%o\n", cases, fixture.race, fd, (unsigned int)st.st_mode);
    CHECK(false);
    return NULL;
}

static int home_test_fstat(int fd, struct stat *st)
{
    if (fstat(fd, st) < 0) return -1;
    struct metadata *entry = metadata_for(fd);
    if (entry->stat_error) { errno = entry->stat_error; return -1; }
    st->st_uid = entry->uid; st->st_gid = entry->gid;
    if (entry->different_device) ++st->st_dev;
    return 0;
}

static long home_test_syscall(long number, ...)
{
    va_list args;
    va_start(args, number);
    long result = -1;
    if (number == SYS_openat2) {
        int directory = va_arg(args, int);
        const char *name = va_arg(args, const char *);
        const struct open_how *how = va_arg(args, const struct open_how *);
        size_t size = va_arg(args, size_t);
        CHECK(directory >= 0 && (!strcmp(name, ".aicode") || !strcmp(name, "tool-output")));
        CHECK(size == sizeof(*how));
        CHECK((how->flags & (O_NOFOLLOW | O_CLOEXEC)) == (O_NOFOLLOW | O_CLOEXEC));
        CHECK((how->resolve & (RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS)) ==
            (RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
        if (!(how->flags & O_PATH)) {
            CHECK((how->flags & (O_DIRECTORY | O_NONBLOCK)) == (O_DIRECTORY | O_NONBLOCK));
            CHECK(how->resolve & RESOLVE_NO_XDEV);
        }
        if (fixture.open_failure && !strcmp(name, fixture.open_failure)) errno = fixture.open_error;
        else result = syscall(number, directory, name, how, size);
    } else if (number == SYS_statx) {
        int fd = va_arg(args, int);
        const char *name = va_arg(args, const char *);
        int flags = va_arg(args, int);
        unsigned int mask = va_arg(args, unsigned int);
        struct statx *sx = va_arg(args, struct statx *);
        CHECK(!*name && flags == (AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW) && mask == STATX_MNT_ID);
        result = syscall(number, fd, name, flags, mask, sx);
        if (result == 0) {
            struct metadata *entry = metadata_for(fd);
            if (entry->different_mount) ++sx->stx_mnt_id;
            if (entry->missing_mount) sx->stx_mask &= ~STATX_MNT_ID;
        }
    } else CHECK(false);
    va_end(args);
    return result;
}

static void write_file(int directory, const char *name, const char *content)
{
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    CHECK(fd >= 0);
    CHECK(write(fd, content, strlen(content)) == (ssize_t)strlen(content));
    CHECK(close(fd) == 0);
}

static void check_file(int directory, const char *name, const char *expected)
{
    int fd = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    CHECK(fd >= 0);
    char contents[64];
    ssize_t count = read(fd, contents, sizeof(contents));
    CHECK(count == (ssize_t)strlen(expected) && !memcmp(contents, expected, (size_t)count));
    CHECK(close(fd) == 0);
}

static int home_test_mkdirat(int directory, const char *name, mode_t mode)
{
    CHECK(!strcmp(name, "tool-output") && mode == 0700);
    CHECK(metadata_for(directory) == &metadata[fixture.config_metadata]);
    ++fixture.mkdirs;
    if (fixture.mkdir_error) { errno = fixture.mkdir_error; return -1; }
    if (fixture.race == CONFIG_SWAP) {
        CHECK(renameat(fixture.home, ".aicode", fixture.home, "config-held") == 0);
        CHECK(symlinkat("../outside", fixture.home, ".aicode") == 0);
        (void)remember(fixture.home, ".aicode", 0, 0);
        ++fixture.swaps;
    } else if (fixture.race == HOME_SWAP) {
        CHECK(renameat(fixture.root, "home", fixture.root, "home-held") == 0);
        CHECK(symlinkat("outside", fixture.root, "home") == 0);
        ++fixture.swaps;
    } else if (fixture.race == COMPETING_APP || fixture.race == COMPETING_FOREIGN) {
        CHECK(mkdirat(directory, name, 0750) == 0);
        fixture.output_metadata = remember(directory, name,
            fixture.race == COMPETING_APP ? APP_UID : APP_UID + 1, APP_GID);
        write_file(directory, "tool-output/kept", "competing directory");
        ++fixture.swaps;
    }
    int result = mkdirat(directory, name, mode);
    if (result < 0) return result;
    fixture.created_metadata = remember(directory, name, 0, 0);
    fixture.output_metadata = fixture.created_metadata;
    struct metadata *created = &metadata[fixture.created_metadata];
    if (fixture.created_fault == 1) created->uid = APP_UID + 1;
    if (fixture.created_fault == 2) created->different_device = true;
    if (fixture.created_fault == 3) created->different_mount = true;
    if (fixture.created_fault == 4) created->missing_mount = true;
    if (fixture.created_fault == 5) created->stat_error = EIO;
    if (fixture.race == LEAF_SWAP || fixture.race == LEAF_DIRECTORY_SWAP) {
        CHECK(renameat(directory, name, directory, "created-held") == 0);
        if (fixture.race == LEAF_SWAP) CHECK(symlinkat("../../outside", directory, name) == 0);
        else {
            CHECK(mkdirat(directory, name, 0750) == 0);
            fixture.output_metadata = remember(directory, name, APP_UID, APP_GID);
            write_file(directory, "tool-output/kept", "replacement directory");
        }
        ++fixture.swaps;
    }
    return result;
}

static int home_test_fchown(int fd, uid_t uid, gid_t gid)
{
    CHECK(!(fcntl(fd, F_GETFL) & O_PATH));
    CHECK(uid == APP_UID && gid == APP_GID);
    CHECK(metadata_for(fd) == &metadata[fixture.created_metadata]);
    ++fixture.chowns;
    if (fixture.chown_error) { errno = fixture.chown_error; return -1; }
    if (!fixture.chown_unchanged) { metadata_for(fd)->uid = uid; metadata_for(fd)->gid = gid; }
    return 0;
}

static int home_test_fchmod(int fd, mode_t mode)
{
    CHECK(!(fcntl(fd, F_GETFL) & O_PATH));
    CHECK(mode == 0700 && fixture.chowns == 1);
    CHECK(metadata_for(fd) == &metadata[fixture.created_metadata]);
    ++fixture.chmods;
    if (fixture.chmod_error) { errno = fixture.chmod_error; return -1; }
    return fchmod(fd, fixture.chmod_wrong ? 0755 : mode);
}

static void setup(bool config, bool output)
{
    char path[] = "/tmp/ajiu-home-target-XXXXXX";
    CHECK(mkdtemp(path) != NULL);
    memset(metadata, 0, sizeof(metadata)); metadata_count = 0;
    fixture = (struct home_fixture){ .root = -1, .home = -1, .config = -1, .outside = -1,
        .output_metadata = SIZE_MAX, .created_metadata = SIZE_MAX };
    CHECK(strlen(path) < sizeof(fixture.path)); strcpy(fixture.path, path);
    fixture.root = open(path, DF);
    CHECK(fixture.root >= 0 && mkdirat(fixture.root, "home", 0700) == 0 && mkdirat(fixture.root, "outside", 0700) == 0);
    fixture.home = openat(fixture.root, "home", DF); fixture.outside = openat(fixture.root, "outside", DF);
    CHECK(fixture.home >= 0 && fixture.outside >= 0);
    fixture.home_metadata = remember(fixture.root, "home", APP_UID, APP_GID);
    write_file(fixture.home, ".instance", "verified-instance");
    write_file(fixture.outside, "sentinel", "outside unchanged");
    if (config) {
        CHECK(mkdirat(fixture.home, ".aicode", 0700) == 0);
        fixture.config = openat(fixture.home, ".aicode", DF);
        CHECK(fixture.config >= 0);
        fixture.config_metadata = remember(fixture.home, ".aicode", APP_UID, APP_GID);
    }
    if (output) {
        CHECK(config && mkdirat(fixture.config, "tool-output", 0750) == 0);
        fixture.output_metadata = remember(fixture.config, "tool-output", APP_UID, APP_GID);
        write_file(fixture.config, "tool-output/kept", "previous output");
    }
    ++cases;
}

static unsigned int descriptor_count(void)
{
    DIR *dir = opendir("/proc/self/fd");
    CHECK(dir != NULL);
    unsigned int count = 0;
    while (readdir(dir)) ++count;
    CHECK(closedir(dir) == 0);
    return count;
}

static int prepare_target_for(char *target)
{
    struct ajiu_launch launch = { .uid = APP_UID, .gid = APP_GID, .mountc = target ? 1U : 0U };
    launch.mounts[0] = (struct ajiu_launch_mount){ .kind = AJIU_MOUNT_DIRECTORY, .target_path = target };
    unsigned int before = descriptor_count();
    int result = prepare_home_tool_output(fixture.home, &launch), saved = errno;
    CHECK(descriptor_count() == before);
    errno = saved;
    return result;
}

static int prepare_target(void)
{
    return prepare_target_for("/root/.aicode/tool-output");
}

static void absent(int directory, const char *name)
{
    struct stat st;
    CHECK(fstatat(directory, name, &st, AT_SYMLINK_NOFOLLOW) == -1 && errno == ENOENT);
}

static void remove_contents(int fd)
{
    DIR *dir = fdopendir(dup(fd));
    CHECK(dir != NULL);
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct stat st;
        CHECK(fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0);
        if (S_ISDIR(st.st_mode)) {
            int child = openat(fd, entry->d_name, DF);
            CHECK(child >= 0);
            remove_contents(child);
            CHECK(close(child) == 0 && unlinkat(fd, entry->d_name, AT_REMOVEDIR) == 0);
        } else CHECK(unlinkat(fd, entry->d_name, 0) == 0);
    }
    CHECK(closedir(dir) == 0);
}

static void teardown(void)
{
    check_file(fixture.home, ".instance", "verified-instance");
    check_file(fixture.outside, "sentinel", "outside unchanged");
    absent(fixture.outside, "tool-output");
    absent(fixture.outside, ".aicode");
    if (fixture.config >= 0) CHECK(close(fixture.config) == 0);
    CHECK(close(fixture.home) == 0 && close(fixture.outside) == 0);
    remove_contents(fixture.root);
    CHECK(close(fixture.root) == 0 && rmdir(fixture.path) == 0);
}

static void check_unrequested_target(void)
{
    char *targets[] = { NULL, "/root", "/root/workspace", "/root/.aicode/tool-output-other" };
    for (size_t i = 0; i < sizeof(targets) / sizeof(*targets); ++i) {
        setup(false, false);
        metadata[fixture.home_metadata].stat_error = EIO;
        CHECK(prepare_target_for(targets[i]) == 0 && !fixture.mkdirs && !fixture.chowns && !fixture.chmods);
        absent(fixture.home, ".aicode");
        teardown();
    }
}

static void check_old_home(void)
{
    setup(true, false);
    CHECK(prepare_target() == 0 && fixture.mkdirs == 1 && fixture.chowns == 1 && fixture.chmods == 1);
    struct stat st;
    CHECK(fstatat(fixture.config, "tool-output", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISDIR(st.st_mode) &&
        (st.st_mode & 07777) == 0700);
    CHECK(metadata[fixture.output_metadata].uid == APP_UID && metadata[fixture.output_metadata].gid == APP_GID);
    ino_t inode = st.st_ino;
    write_file(fixture.config, "tool-output/kept", "new output");
    CHECK(prepare_target() == 0 && fixture.mkdirs == 1 && fixture.chowns == 1 && fixture.chmods == 1);
    CHECK(fstatat(fixture.config, "tool-output", &st, AT_SYMLINK_NOFOLLOW) == 0 && st.st_ino == inode);
    check_file(fixture.config, "tool-output/kept", "new output");
    teardown();
    setup(true, true);
    struct stat original;
    CHECK(fstatat(fixture.config, "tool-output", &original, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(prepare_target() == 0 && !fixture.mkdirs && !fixture.chowns && !fixture.chmods);
    CHECK(fstatat(fixture.config, "tool-output", &st, AT_SYMLINK_NOFOLLOW) == 0 && same(&st, &original));
    check_file(fixture.config, "tool-output/kept", "previous output");
    teardown();
    setup(false, false);
    CHECK(prepare_target() == -1 && errno == ENOENT && !fixture.mkdirs && !fixture.chowns);
    absent(fixture.home, ".aicode");
    teardown();
}

static void check_files_and_links(void)
{
    for (int leaf = 0; leaf < 2; ++leaf) for (int symbolic = 0; symbolic < 2; ++symbolic) {
        setup(leaf != 0, false);
        int directory = leaf ? fixture.config : fixture.home;
        const char *name = leaf ? "tool-output" : ".aicode";
        if (symbolic) CHECK(symlinkat(leaf ? "../../outside" : "../outside", directory, name) == 0);
        else write_file(directory, name, "ordinary file");
        CHECK(prepare_target() == -1 && !fixture.mkdirs && !fixture.chowns && !fixture.chmods);
        if (symbolic) {
            struct stat st;
            CHECK(fstatat(directory, name, &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode));
        } else check_file(directory, name, "ordinary file");
        teardown();
    }
}

static void check_identity_and_mounts(void)
{
    for (int part = 0; part < 3; ++part) for (int fault = 0; fault < 6; ++fault) {
        if (part == 0 && (fault == 2 || fault == 3)) continue;
        setup(true, part == 2);
        size_t index = part == 0 ? fixture.home_metadata : part == 1 ? fixture.config_metadata : fixture.output_metadata;
        struct metadata *entry = &metadata[index];
        if (fault == 0) ++entry->uid;
        if (fault == 1) ++entry->gid;
        if (fault == 2) entry->different_device = true;
        if (fault == 3) entry->different_mount = true;
        if (fault == 4) entry->missing_mount = true;
        if (fault == 5) entry->stat_error = EIO;
        CHECK(prepare_target() == -1 && !fixture.mkdirs && !fixture.chowns && !fixture.chmods);
        if (part == 2) check_file(fixture.config, "tool-output/kept", "previous output");
        else absent(fixture.config, "tool-output");
        teardown();
    }
    for (int part = 0; part < 2; ++part) {
        setup(true, part != 0);
        fixture.open_failure = part ? "tool-output" : ".aicode";
        fixture.open_error = EXDEV;
        CHECK(prepare_target() == -1 && errno == EXDEV && !fixture.mkdirs && !fixture.chowns);
        teardown();
    }
}

static void check_races(void)
{
    for (enum race race = CONFIG_SWAP; race <= COMPETING_FOREIGN; ++race) {
        setup(true, false);
        fixture.race = race;
        int result = prepare_target();
        CHECK(fixture.swaps == 1 && fixture.mkdirs == 1);
        CHECK(result == (race == HOME_SWAP || race == COMPETING_APP ? 0 : -1));
        if (race == CONFIG_SWAP || race == HOME_SWAP) {
            struct stat st;
            CHECK(fstatat(fixture.config, "tool-output", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISDIR(st.st_mode));
            CHECK(fixture.chowns == 1 && fixture.chmods == 1);
        } else {
            CHECK(!fixture.chowns && !fixture.chmods);
            if (race == LEAF_DIRECTORY_SWAP) check_file(fixture.config, "tool-output/kept", "replacement directory");
            if (race == COMPETING_APP || race == COMPETING_FOREIGN)
                check_file(fixture.config, "tool-output/kept", "competing directory");
        }
        teardown();
    }
}

static void check_creation_errors(void)
{
    for (int fault = 1; fault <= 5; ++fault) {
        setup(true, false);
        fixture.created_fault = fault;
        CHECK(prepare_target() == -1 && fixture.mkdirs == 1 && !fixture.chowns && !fixture.chmods);
        teardown();
    }
    for (int fault = 0; fault < 2; ++fault) {
        setup(true, false);
        fixture.chown_unchanged = fault == 0;
        fixture.chmod_wrong = fault == 1;
        CHECK(prepare_target() == -1 && fixture.mkdirs == 1 && fixture.chowns == 1 && fixture.chmods == 1);
        teardown();
    }
    for (int failure = 0; failure < 3; ++failure) {
        setup(true, false);
        if (failure == 0) fixture.mkdir_error = EACCES;
        if (failure == 1) fixture.chown_error = EPERM;
        if (failure == 2) fixture.chmod_error = EPERM;
        CHECK(prepare_target() == -1 && errno == (failure ? EPERM : EACCES));
        CHECK(fixture.mkdirs == 1 && fixture.chowns == (failure ? 1U : 0U) && fixture.chmods == (failure == 2 ? 1U : 0U));
        teardown();
    }
}

int main(void)
{
    check_unrequested_target();
    check_old_home();
    check_files_and_links();
    check_identity_and_mounts();
    check_races();
    check_creation_errors();
    printf("Home target checks passed: %u; cases=%u; real openat2/mkdirat/rename/symlinks, mocked ownership/mount faults, no production main/chown/mount/Root operations.\n", checks, cases);
    return 0;
}
