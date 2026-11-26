#include "runtime_launch.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "Launch check line %d: %s\n", __LINE__, #c); return 1; } ++checks; } while (0)

struct fixture {
    unsigned char bytes[AJIU_MAX_FRAME_BYTES + 1];
    size_t used, uid, gid, group_count, groups, mode, lease, timeout, dev, ino;
    size_t owner, cwd, argc, argv0, envc, env0, env1, mountc, kind, ro, source_dev, source_ino, source, target;
};

static void put32(unsigned char *p, uint32_t n)
{
    p[0] = (unsigned char)(n >> 24); p[1] = (unsigned char)(n >> 16);
    p[2] = (unsigned char)(n >> 8); p[3] = (unsigned char)n;
}

static size_t add32(struct fixture *f, uint32_t n)
{
    size_t offset = f->used; put32(f->bytes + f->used, n); f->used += 4; return offset;
}

static size_t add64(struct fixture *f, uint64_t n)
{
    size_t offset = add32(f, (uint32_t)(n >> 32)); add32(f, (uint32_t)n); return offset;
}

static size_t text(struct fixture *f, const char *s)
{
    size_t n = strlen(s), offset = add32(f, (uint32_t)n);
    memcpy(f->bytes + f->used, s, n); f->used += n; return offset;
}

static void fixture(struct fixture *f, const char *target, const char *source, const char *env1)
{
    memset(f, 0, sizeof(*f));
    for (size_t i = 0; i < 32; ++i) f->bytes[f->used++] = (unsigned char)(i + 1);
    f->uid = add32(f, 10445); f->gid = add32(f, 10445); f->group_count = add32(f, 6);
    f->groups = f->used;
    add32(f, 1079); add32(f, 3003); add32(f, 9997); add32(f, 20445); add32(f, 50445); add32(f, 99909997);
    f->mode = add32(f, 0); f->lease = add32(f, 15000); f->timeout = add32(f, 1800000);
    f->dev = add64(f, UINT64_C(0x100000002)); f->ino = add64(f, UINT64_C(0x300000004));
    text(f, "0123456789abcdef01234567"); text(f, "abcdef0123456789abcdef01");
    f->owner = text(f, "workspace:\xe9\x98\xbf\xe4\xb9\x9d\xf0\x9f\x8f\xa0");
    f->cwd = text(f, "/root/workspace"); f->argc = add32(f, 3);
    f->argv0 = text(f, "sh"); text(f, "-c"); text(f, "");
    f->envc = add32(f, 2); f->env0 = text(f, "PATH=/bin:/usr/bin"); f->env1 = text(f, env1);
    f->mountc = add32(f, 1); f->kind = add32(f, AJIU_MOUNT_DIRECTORY); f->ro = add32(f, 0);
    f->source_dev = add64(f, 123); f->source_ino = add64(f, 456);
    f->source = text(f, source); f->target = text(f, target);
}

static void valid(struct fixture *f)
{
    fixture(f, "/root/workspace", "/data/user/0/com.ajiu.debug/files/projects/example", "LD_PRELOAD=/tmp/guest.so");
}

static int parse(const struct fixture *f)
{
    struct ajiu_launch l;
    int rc = ajiu_launch_parse(f->bytes, f->used, &l);
    if (!rc) ajiu_launch_free(&l);
    return rc;
}

static int fixture_stdin(void)
{
    unsigned char *bytes = malloc(AJIU_MAX_FRAME_BYTES + 1);
    if (!bytes) return 2;
    size_t size = fread(bytes, 1, AJIU_MAX_FRAME_BYTES + 1, stdin);
    struct ajiu_launch l;
    int rc = ferror(stdin) ? -1 : ajiu_launch_parse(bytes, size, &l);
    free(bytes);
    if (rc < 0) { fputs("Launch fixture rejected.\n", stderr); return 1; }
    ajiu_launch_free(&l);
    puts("Launch fixture accepted; no process, mount or file writes.");
    return 0;
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--fixture-stdin")) return fixture_stdin();
    if (argc != 1) return 2;
    int checks = 0;
    struct fixture *f = malloc(sizeof(*f));
    CHECK(f != NULL);
    valid(f);
    struct ajiu_launch l;
    CHECK(ajiu_launch_parse(f->bytes, f->used, &l) == 0);
    CHECK(l.uid == 10445 && l.gid == l.uid && l.group_count == 6 && l.groups[5] == 99909997);
    CHECK(l.rootfs_dev == UINT64_C(0x100000002) && l.rootfs_ino == UINT64_C(0x300000004));
    CHECK(l.argc == 3 && !strcmp(l.argv[2], "") && l.argv[3] == NULL && l.env[2] == NULL);
    CHECK(!strcmp(l.env[1], "LD_PRELOAD=/tmp/guest.so"));
    CHECK(l.mountc == 1 && l.mounts[0].source_dev == 123 && l.mounts[0].source_ino == 456);
    ajiu_launch_free(&l);
    for (size_t size = 0; size < f->used; ++size) {
        CHECK(ajiu_launch_parse(f->bytes, size, &l) == -1);
        CHECK(l.argc == 0 && l.instance_id == NULL);
    }
    CHECK(ajiu_launch_parse(NULL, 0, &l) == -1);
    CHECK(ajiu_launch_parse(f->bytes, AJIU_MAX_FRAME_BYTES + 1, &l) == -1);
    f->bytes[f->used++] = 0; CHECK(parse(f) == -1);
    valid(f); memset(f->bytes, 0, 32); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->uid, 0); put32(f->bytes + f->gid, 0); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->uid, 9999); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->uid, UINT32_MAX); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->gid, 10446); CHECK(parse(f) == -1);
    for (uint32_t group = 0; group < 10000; ++group) {
        if (group == 1079 || group == 3003 || group == 9997) continue;
        CHECK(!ajiu_launch_group_allowed(10445, group));
    }
    CHECK(ajiu_launch_group_allowed(10445, 10445));
    CHECK(ajiu_launch_group_allowed(10445, 30445));
    CHECK(ajiu_launch_group_allowed(10445, 40445));
    CHECK(ajiu_launch_group_allowed(10445, 50445));
    CHECK(ajiu_launch_group_allowed(110445, 120445));
    CHECK(ajiu_launch_group_allowed(110445, 109997));
    CHECK(!ajiu_launch_group_allowed(10445, 20446));
    valid(f); put32(f->bytes + f->group_count, 33); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->groups, 0); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->groups, 3003); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->mode, 2); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->lease, 4999); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->lease, 60001); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->lease, 5000); put32(f->bytes + f->timeout, 0); CHECK(parse(f) == 0);
    valid(f); put32(f->bytes + f->timeout, 1800001); CHECK(parse(f) == -1);
    valid(f); memset(f->bytes + f->dev, 0, 8); CHECK(parse(f) == -1);
    valid(f); memset(f->bytes + f->ino, 0, 8); CHECK(parse(f) == -1);
    valid(f); f->bytes[f->owner + 4] = 0; CHECK(parse(f) == -1);
    static const unsigned char bad_utf8[][4] = { {0xc0,0x80,0x61,0x61}, {0xed,0xa0,0x80,0x61},
        {0xf4,0x90,0x80,0x80}, {0x80,0x61,0x61,0x61}, {0xe2,0x28,0xa1,0x61} };
    for (size_t i = 0; i < sizeof(bad_utf8) / sizeof(*bad_utf8); ++i) {
        valid(f); memcpy(f->bytes + f->owner + 4, bad_utf8[i], 4); CHECK(parse(f) == -1);
    }
    valid(f); f->bytes[f->owner + 4 + strlen("workspace:\xe9\x98\xbf\xe4\xb9\x9d\xf0\x9f\x8f\xa0") - 1] = 0xf0; CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->owner, 513); CHECK(parse(f) == -1);
    valid(f); f->bytes[f->cwd + 4] = 'r'; CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->argc, 0); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->argc, 257); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->argv0, 65537); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->argv0, 0); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->envc, 257); CHECK(parse(f) == -1);
    fixture(f, "/root/workspace", "/data/user/0/com.ajiu.debug/files/projects/example", "PATH=duplicate"); CHECK(parse(f) == -1);
    static const char *const bad_env[] = { "NOEQUAL", "=empty", "0KEY=bad", "A-B=bad", "\xc3\xa9=bad" };
    for (size_t i = 0; i < sizeof(bad_env) / sizeof(*bad_env); ++i) {
        fixture(f, "/root/workspace", "/data/user/0/com.ajiu.debug/files/projects/example", bad_env[i]); CHECK(parse(f) == -1);
    }
    valid(f); put32(f->bytes + f->mountc, 33); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->kind, 3); CHECK(parse(f) == -1);
    valid(f); put32(f->bytes + f->ro, 2); CHECK(parse(f) == -1);
    valid(f); memset(f->bytes + f->source_dev, 0, 8); CHECK(parse(f) == -1);
    valid(f); memset(f->bytes + f->source_ino, 0, 8); CHECK(parse(f) == -1);
    static const char *const bad_targets[] = { "/", "/proc", "/dev/pts", "/sys", "/tmp/x", "/run/x", "/etc", "/bin",
        "/root/../etc", "/root/./workspace", "/root//workspace", "/root/workspace/", "/root/workspace/child",
        "/root/.aicode", "/root/.aicode/credentials", "/root/.aicode/skills/child", "/root/.aicode/provider" };
    for (size_t i = 0; i < sizeof(bad_targets) / sizeof(*bad_targets); ++i) {
        fixture(f, bad_targets[i], "/data/user/0/com.ajiu.debug/files/projects/example", "A=B"); CHECK(parse(f) == -1);
    }
    static const char *const bad_sources[] = { "/", "/data", "/data/system", "/data/user/0/com.ajiu.debug",
        "/data/user/0/com.ajiu.debug/files", "/data/user/0/com.ajiu.debug/files/aicode", "/data/user/0/com.other/files/projects/a",
        "/data/user/0/com.ajiu.debug/files/projects/../aicode", "/proc/self/fd/3", "/data/user/0/com.ajiu.debug/files/projects//x" };
    for (size_t i = 0; i < sizeof(bad_sources) / sizeof(*bad_sources); ++i) {
        fixture(f, "/root/workspace", bad_sources[i], "A=B"); CHECK(parse(f) == -1);
    }
    fixture(f, "/root", "/data/user/0/com.ajiu/files/chroot-home/0123456789abcdef01234567", "A=B"); CHECK(parse(f) == 0);
    fixture(f, "/root", "/data/user/0/com.ajiu/files/aicode", "A=B"); CHECK(parse(f) == -1);
    fixture(f, "/root/.aicode/skills", "/data/user/0/com.ajiu/files/aicode/skills", "A=B");
    CHECK(parse(f) == -1); put32(f->bytes + f->ro, 1); CHECK(parse(f) == 0);
    fixture(f, "/root/.aicode/plugins", "/data/user/0/com.ajiu/files/aicode/plugins", "A=B"); CHECK(parse(f) == 0);
    fixture(f, "/etc/resolv.conf", "/data/user/0/com.ajiu/files/chroot-dns/0123456789abcdef01234567/resolv.conf", "A=B");
    put32(f->bytes + f->kind, 2); CHECK(parse(f) == -1); put32(f->bytes + f->ro, 1); CHECK(parse(f) == 0);
    valid(f); put32(f->bytes + f->mountc, 0); f->used = f->mountc + 4; CHECK(parse(f) == 0);
    valid(f);
    memmove(f->bytes + f->groups, f->bytes + f->groups + 24, f->used - f->groups - 24);
    f->used -= 24; put32(f->bytes + f->group_count, 0); CHECK(parse(f) == 0);
    valid(f);
    size_t entry_bytes = f->used - f->kind;
    memcpy(f->bytes + f->used, f->bytes + f->kind, entry_bytes); f->used += entry_bytes;
    put32(f->bytes + f->mountc, 2); CHECK(parse(f) == -1);
    valid(f); f->used = f->argc; add32(f, 256);
    for (size_t i = 0; i < 256; ++i) text(f, "a");
    add32(f, 0); add32(f, 0); CHECK(parse(f) == 0);
    valid(f); f->used = f->argc; add32(f, 2); text(f, "sh");
    char *longarg = malloc(65538); CHECK(longarg != NULL);
    memset(longarg, 'a', 65536); longarg[65536] = 0;
    text(f, longarg); add32(f, 0); add32(f, 0); CHECK(parse(f) == 0);
    valid(f); f->used = f->argc; add32(f, 2); text(f, "sh");
    longarg[65536] = 'a'; longarg[65537] = 0;
    text(f, longarg); add32(f, 0); add32(f, 0); CHECK(parse(f) == -1);
    free(longarg);
    valid(f); f->used = f->cwd;
    char longpath[260]; longpath[0] = '/'; memset(longpath + 1, 'x', 256); longpath[257] = 0;
    text(f, longpath); add32(f, 1); text(f, "sh"); add32(f, 0); add32(f, 0); CHECK(parse(f) == -1);
    static const char *const bad_cwd[] = { "", "root", "/root/..", "/root/./workspace", "/root//workspace", "/root/workspace/" };
    for (size_t i = 0; i < sizeof(bad_cwd) / sizeof(*bad_cwd); ++i) {
        valid(f); f->used = f->cwd; text(f, bad_cwd[i]); add32(f, 1); text(f, "sh"); add32(f, 0); add32(f, 0);
        CHECK(parse(f) == -1);
    }
    valid(f); f->used = f->cwd; text(f, "/"); add32(f, 1); text(f, "sh"); add32(f, 0); add32(f, 0); CHECK(parse(f) == 0);
    struct ajiu_launch_session s;
    struct ajiu_frame_header h;
    ajiu_launch_session_init(&s);
    valid(f); h = (struct ajiu_frame_header){ AJIU_LAUNCH, (uint32_t)f->used };
    CHECK(ajiu_launch_session_accept(&s, &h, f->bytes, &l) == 0 && s.launch_seen && s.phase == AJIU_PHASE_PREPARED);
    ajiu_launch_free(&l);
    CHECK(ajiu_launch_session_accept(&s, &h, f->bytes, &l) == -1 && s.phase == AJIU_PHASE_CLOSED);
    h = (struct ajiu_frame_header){ AJIU_GO, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == -1);
    ajiu_launch_session_init(&s); h = (struct ajiu_frame_header){ AJIU_LAUNCH, (uint32_t)f->used };
    CHECK(ajiu_launch_session_accept(&s, &h, f->bytes, &l) == 0); ajiu_launch_free(&l);
    h = (struct ajiu_frame_header){ AJIU_HEARTBEAT, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == 0);
    h = (struct ajiu_frame_header){ AJIU_GO, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == 0 && s.go_seen && s.phase == AJIU_PHASE_RUNNING);
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == -1 && s.phase == AJIU_PHASE_CLOSED);
    s = (struct ajiu_launch_session){ .phase = AJIU_PHASE_RUNNING, .launch_seen = 1, .go_seen = 1 };
    h = (struct ajiu_frame_header){ AJIU_STDIN_EOF, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == 0);
    h = (struct ajiu_frame_header){ AJIU_STDIN, 1 };
    CHECK(ajiu_launch_session_accept(&s, &h, (const unsigned char *)"x", NULL) == -1);
    s = (struct ajiu_launch_session){ .phase = AJIU_PHASE_PREPARED, .launch_seen = 1 };
    h = (struct ajiu_frame_header){ AJIU_STOP, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == 0 && s.phase == AJIU_PHASE_STOPPING);
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == -1);
    ajiu_launch_session_init(&s); h = (struct ajiu_frame_header){ AJIU_GO, 0 };
    CHECK(ajiu_launch_session_accept(&s, &h, NULL, NULL) == -1 && s.phase == AJIU_PHASE_CLOSED);
    ajiu_launch_session_init(&s); h = (struct ajiu_frame_header){ AJIU_STDIN, 1 };
    CHECK(ajiu_launch_session_accept(&s, &h, (const unsigned char *)"x", NULL) == -1);
    s = (struct ajiu_launch_session){ .phase = AJIU_PHASE_RUNNING, .launch_seen = 1, .go_seen = 1 };
    h = (struct ajiu_frame_header){ AJIU_LAUNCH, (uint32_t)f->used };
    CHECK(ajiu_launch_session_accept(&s, &h, f->bytes, &l) == -1 && s.phase == AJIU_PHASE_CLOSED);
    s = (struct ajiu_launch_session){ .phase = AJIU_PHASE_RUNNING, .launch_seen = 1, .go_seen = 1 };
    h = (struct ajiu_frame_header){ AJIU_SIGNAL, 1 };
    const unsigned char invalid_signal = 9;
    CHECK(ajiu_launch_session_accept(&s, &h, &invalid_signal, NULL) == -1);
    free(f);
    printf("Native launch parsing checks passed: %d; no process, mount or file writes.\n", checks);
    return 0;
}
