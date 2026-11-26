#include "runtime_launch.h"

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct cursor { const unsigned char *data; size_t left; };

static int take(struct cursor *c, size_t size, const unsigned char **out)
{
    if (size > c->left) { errno = EPROTO; return -1; }
    *out = c->data;
    c->data += size;
    c->left -= size;
    return 0;
}

static int u32(struct cursor *c, uint32_t *out)
{
    const unsigned char *p;
    if (take(c, 4, &p) < 0) return -1;
    *out = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
    return 0;
}

static int u64(struct cursor *c, uint64_t *out)
{
    uint32_t high, low;
    if (u32(c, &high) < 0 || u32(c, &low) < 0) return -1;
    *out = ((uint64_t)high << 32) | low;
    return 0;
}

static int utf8(const unsigned char *s, size_t n)
{
    for (size_t i = 0; i < n;) {
        unsigned char c = s[i++];
        if (!c) return 0;
        if (c < 0x80) continue;
        uint32_t v, minimum;
        size_t more;
        if (c >= 0xc2 && c <= 0xdf) { v = c & 0x1fU; more = 1; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { v = c & 0x0fU; more = 2; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { v = c & 7U; more = 3; minimum = 0x10000; }
        else return 0;
        if (more > n - i) return 0;
        while (more--) {
            unsigned char b = s[i++];
            if ((b & 0xc0U) != 0x80) return 0;
            v = (v << 6) | (b & 0x3fU);
        }
        if (v < minimum || v > 0x10ffff || (v >= 0xd800 && v <= 0xdfff)) return 0;
    }
    return 1;
}

static int string(struct cursor *c, char **out, uint32_t maximum, int empty)
{
    uint32_t n;
    const unsigned char *s;
    if (u32(c, &n) < 0 || n > maximum || (!empty && !n) || take(c, n, &s) < 0 || !utf8(s, n)) {
        errno = EPROTO;
        return -1;
    }
    char *p = malloc((size_t)n + 1);
    if (!p) return -1;
    memcpy(p, s, n);
    p[n] = 0;
    *out = p;
    return 0;
}

static int hex24(const char *s)
{
    if (strlen(s) != 24) return 0;
    for (size_t i = 0; i < 24; ++i)
        if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return 0;
    return 1;
}

static int path(const char *s, int root)
{
    if (!s || s[0] != '/' || (!root && !s[1])) return 0;
    if (!s[1]) return 1;
    for (const char *p = s + 1; *p;) {
        const char *end = strchr(p, '/');
        size_t n = end ? (size_t)(end - p) : strlen(p);
        if (!n || n > 255 || (n == 1 && p[0] == '.') || (n == 2 && p[0] == '.' && p[1] == '.')) return 0;
        if (!end) return 1;
        p = end + 1;
        if (!*p) return 0;
    }
    return 0;
}

static int below(const char *s, const char *parent)
{
    size_t n = strlen(parent);
    return !strncmp(s, parent, n) && (!s[n] || s[n] == '/');
}

int ajiu_launch_group_allowed(uint32_t uid, uint32_t group)
{
    if (uid < 10000 || uid > INT_MAX || group > INT_MAX) return 0;
    if (group == 3003 || group == 9997 || group == 1079 || group == 99909997 || group == uid) return 1;
    uint32_t user = uid / 100000, app = uid % 100000;
    if (group == user * 100000 + 9997) return 1;
    if (app < 10000 || app > 19999) return 0;
    return group == user * 100000 + app + 10000 ||
           group == user * 100000 + app + 20000 ||
           group == user * 100000 + app + 30000 || group == app + 40000;
}

static int aicode_name(const char *name, int *readonly)
{
    static const char *const ro[] = { "skills", "agents", "docs", "bin", "lib", "prompts.custom" };
    static const char *const rw[] = { "memory", "scripts", "plugins" };
    for (size_t i = 0; i < sizeof(ro) / sizeof(*ro); ++i)
        if (!strcmp(name, ro[i])) { *readonly = 1; return 1; }
    for (size_t i = 0; i < sizeof(rw) / sizeof(*rw); ++i)
        if (!strcmp(name, rw[i])) { *readonly = 0; return 1; }
    return 0;
}

int ajiu_launch_target_allowed(const struct ajiu_launch_mount *m)
{
    if (!m || !path(m->target_path, 0) || m->read_only > 1) return 0;
    if (!strcmp(m->target_path, "/etc/resolv.conf"))
        return m->kind == AJIU_MOUNT_FILE && m->read_only == 1;
    if (m->kind != AJIU_MOUNT_DIRECTORY) return 0;
    if (!strcmp(m->target_path, "/root") || !strcmp(m->target_path, "/root/workspace")) return 1;
    const char *prefix = "/root/.aicode/";
    if (strncmp(m->target_path, prefix, strlen(prefix))) return 0;
    int ro;
    return aicode_name(m->target_path + strlen(prefix), &ro) && (!ro || m->read_only);
}

static int app_files(const struct ajiu_launch *l, const char *source, const char **relative)
{
    char prefix[64];
    int n = snprintf(prefix, sizeof(prefix), "/data/user/%u/", l->uid / 100000);
    if (n < 0 || (size_t)n >= sizeof(prefix) || strncmp(source, prefix, (size_t)n)) return 0;
    const char *pkg = source + n, *slash = strchr(pkg, '/');
    if (!slash) return 0;
    size_t len = (size_t)(slash - pkg);
    if (!((len == 8 && !memcmp(pkg, "com.ajiu", 8)) ||
          (len == 14 && !memcmp(pkg, "com.ajiu.debug", 14)) ||
          (len == 13 && !memcmp(pkg, "com.ajiu.beta", 13)))) return 0;
    if (strncmp(slash, "/files/", 7)) return 0;
    *relative = slash + 7;
    return 1;
}

int ajiu_launch_source_allowed(const struct ajiu_launch *l, const struct ajiu_launch_mount *m)
{
    if (!l || !m || !l->instance_id || !path(m->source_path, 0) || !m->source_dev || !m->source_ino ||
        !ajiu_launch_target_allowed(m)) return 0;
    const char *relative = NULL;
    char expected[128];
    if (!strcmp(m->target_path, "/root")) {
        (void)snprintf(expected, sizeof(expected), "chroot-home/%s", l->instance_id);
        return app_files(l, m->source_path, &relative) && !strcmp(relative, expected);
    }
    if (!strcmp(m->target_path, "/etc/resolv.conf")) {
        (void)snprintf(expected, sizeof(expected), "chroot-dns/%s/resolv.conf", l->instance_id);
        return app_files(l, m->source_path, &relative) && !strcmp(relative, expected);
    }
    if (strncmp(m->target_path, "/root/.aicode/", 14) == 0) {
        (void)snprintf(expected, sizeof(expected), "aicode/%s", m->target_path + 14);
        return app_files(l, m->source_path, &relative) && !strcmp(relative, expected);
    }
    static const char *const denied[] = {
        "/proc", "/sys", "/dev", "/system", "/vendor", "/product", "/apex", "/odm", "/etc", "/bin", "/sbin",
        "/lib", "/lib64", "/usr", "/data/system", "/data/misc", "/data/local", "/data/app", "/data/adb",
        "/data/user_de", "/data/data", "/mnt/expand"
    };
    for (size_t i = 0; i < sizeof(denied) / sizeof(*denied); ++i)
        if (below(m->source_path, denied[i])) return 0;
    if (below(m->source_path, "/data/user")) {
        if (!app_files(l, m->source_path, &relative)) return 0;
        if (!strncmp(relative, "projects/", 9) && relative[9]) return 1;
        return 0;
    }
    if (below(m->source_path, "/data") || below(m->source_path, "/mnt")) return 0;
    return below(m->source_path, "/storage/emulated") && strcmp(m->source_path, "/storage/emulated") &&
           strchr(m->source_path + strlen("/storage/emulated/"), '/') != NULL;
}

static int environment(const char *s, size_t *key_length)
{
    const char *equal = strchr(s, '=');
    if (!equal || equal == s) return 0;
    for (const char *p = s; p < equal; ++p) {
        unsigned char c = (unsigned char)*p;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' ||
              (p != s && c >= '0' && c <= '9'))) return 0;
    }
    *key_length = (size_t)(equal - s);
    return 1;
}

void ajiu_launch_free(struct ajiu_launch *l)
{
    if (!l) return;
    free(l->instance_id); free(l->launch_id); free(l->owner_identity); free(l->cwd);
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_ARGS; ++i) free(l->argv[i]);
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_ENV; ++i) free(l->env[i]);
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_MOUNTS; ++i) {
        free(l->mounts[i].source_path); free(l->mounts[i].target_path);
    }
    memset(l, 0, sizeof(*l));
}

int ajiu_launch_parse(const unsigned char *bytes, size_t length, struct ajiu_launch *l)
{
    if (!l) { errno = EINVAL; return -1; }
    memset(l, 0, sizeof(*l));
    if (!bytes || length > AJIU_MAX_FRAME_BYTES) { errno = EPROTO; return -1; }
    struct cursor c = { bytes, length };
    const unsigned char *token;
    if (take(&c, sizeof(l->token), &token) < 0) goto invalid;
    memcpy(l->token, token, sizeof(l->token));
    unsigned char nonzero = 0;
    for (size_t i = 0; i < sizeof(l->token); ++i) nonzero |= l->token[i];
    if (!nonzero || u32(&c, &l->uid) < 0 || u32(&c, &l->gid) < 0 || l->uid < 10000 ||
        l->uid > INT_MAX || l->gid != l->uid || u32(&c, &l->group_count) < 0 ||
        l->group_count > AJIU_LAUNCH_MAX_GROUPS) goto invalid;
    for (uint32_t i = 0; i < l->group_count; ++i) {
        if (u32(&c, &l->groups[i]) < 0 || !ajiu_launch_group_allowed(l->uid, l->groups[i])) goto invalid;
        for (uint32_t j = 0; j < i; ++j) if (l->groups[i] == l->groups[j]) goto invalid;
    }
    if (u32(&c, &l->io_mode) < 0 || l->io_mode > AJIU_IO_PTY || u32(&c, &l->lease_ms) < 0 ||
        l->lease_ms < 5000 || l->lease_ms > 60000 || u32(&c, &l->command_timeout_ms) < 0 ||
        l->command_timeout_ms > 1800000 || u64(&c, &l->rootfs_dev) < 0 || u64(&c, &l->rootfs_ino) < 0 ||
        !l->rootfs_dev || !l->rootfs_ino || string(&c, &l->instance_id, 24, 0) < 0 || !hex24(l->instance_id) ||
        string(&c, &l->launch_id, 24, 0) < 0 || !hex24(l->launch_id) ||
        string(&c, &l->owner_identity, 512, 0) < 0 || string(&c, &l->cwd, AJIU_LAUNCH_MAX_PATH, 0) < 0 ||
        !path(l->cwd, 1) || u32(&c, &l->argc) < 0 || !l->argc || l->argc > AJIU_LAUNCH_MAX_ARGS) goto invalid;
    for (uint32_t i = 0; i < l->argc; ++i)
        if (string(&c, &l->argv[i], AJIU_LAUNCH_MAX_STRING, i != 0) < 0) goto invalid;
    if (u32(&c, &l->envc) < 0 || l->envc > AJIU_LAUNCH_MAX_ENV) goto invalid;
    for (uint32_t i = 0; i < l->envc; ++i) {
        size_t key;
        if (string(&c, &l->env[i], AJIU_LAUNCH_MAX_STRING, 0) < 0 || !environment(l->env[i], &key)) goto invalid;
        for (uint32_t j = 0; j < i; ++j)
            if (!strncmp(l->env[i], l->env[j], key) && l->env[j][key] == '=') goto invalid;
    }
    if (u32(&c, &l->mountc) < 0 || l->mountc > AJIU_LAUNCH_MAX_MOUNTS) goto invalid;
    for (uint32_t i = 0; i < l->mountc; ++i) {
        struct ajiu_launch_mount *m = &l->mounts[i];
        if (u32(&c, &m->kind) < 0 || u32(&c, &m->read_only) < 0 || u64(&c, &m->source_dev) < 0 ||
            u64(&c, &m->source_ino) < 0 || string(&c, &m->source_path, AJIU_LAUNCH_MAX_PATH, 0) < 0 ||
            string(&c, &m->target_path, AJIU_LAUNCH_MAX_PATH, 0) < 0 || !ajiu_launch_source_allowed(l, m)) goto invalid;
        for (uint32_t j = 0; j < i; ++j)
            if (!strcmp(m->target_path, l->mounts[j].target_path)) goto invalid;
    }
    if (c.left) goto invalid;
    return 0;
invalid:
    ajiu_launch_free(l);
    errno = EPROTO;
    return -1;
}

void ajiu_launch_session_init(struct ajiu_launch_session *s)
{
    memset(s, 0, sizeof(*s));
    s->phase = AJIU_PHASE_INITIAL;
}

int ajiu_launch_session_accept(struct ajiu_launch_session *s, const struct ajiu_frame_header *h,
                               const unsigned char *body, struct ajiu_launch *l)
{
    if (!s || !h || ajiu_validate_input(s->phase, h, body) < 0) goto invalid;
    if (h->type == AJIU_LAUNCH) {
        if (s->launch_seen || !l || ajiu_launch_parse(body, h->length, l) < 0) goto invalid;
        s->launch_seen = 1;
        s->phase = AJIU_PHASE_PREPARED;
    } else if (h->type == AJIU_GO) {
        if (!s->launch_seen || s->go_seen) goto invalid;
        s->go_seen = 1;
        s->phase = AJIU_PHASE_RUNNING;
    } else if (h->type == AJIU_STDIN_EOF) {
        if (s->stdin_closed) goto invalid;
        s->stdin_closed = 1;
    } else if (h->type == AJIU_STDIN && s->stdin_closed) goto invalid;
    else if (h->type == AJIU_STOP) s->phase = AJIU_PHASE_STOPPING;
    return 0;
invalid:
    if (s) s->phase = AJIU_PHASE_CLOSED;
    errno = EPROTO;
    return -1;
}
