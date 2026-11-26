#ifndef AJIU_RUNTIME_GUEST_H
#define AJIU_RUNTIME_GUEST_H

#include <linux/capability.h>
#include <linux/securebits.h>
#include <stdbool.h>
#include <stdint.h>

/* Real UID 0 in a writable rootfs, not an AppUID sandbox. Setup/host caps are not guest caps. */
#define AJIU_GUEST_CAPS ((UINT64_C(1) << CAP_CHOWN) | (UINT64_C(1) << CAP_DAC_OVERRIDE) | \
    (UINT64_C(1) << CAP_FOWNER) | (UINT64_C(1) << CAP_FSETID) | (UINT64_C(1) << CAP_KILL) | \
    (UINT64_C(1) << CAP_SETGID) | (UINT64_C(1) << CAP_SETUID) | (UINT64_C(1) << CAP_SETFCAP))
#define AJIU_GUEST_ID_MAX UINT32_C(65535)

/* Keep ordinary root exec semantics, but lock KEEP_CAPS and NO_SETUID_FIXUP off.
 * A full setresuid(_apt, _apt, _apt) therefore clears E/P; ambient raises stay disabled.
 * NNP=1 and nosuid mounts deliberately do not offer sudo/setid/filecap privilege elevation.
 * Root can still chown, set ids, write security.capability and exec ordinary apt/dpkg tools. */
#define AJIU_GUEST_SECUREBITS (SECBIT_NOROOT_LOCKED | SECBIT_NO_SETUID_FIXUP_LOCKED | \
    SECBIT_KEEP_CAPS_LOCKED | SECBIT_NO_CAP_AMBIENT_RAISE | SECBIT_NO_CAP_AMBIENT_RAISE_LOCKED)

/* /proc/status order: effective, permitted, inheritable, bounding, ambient. */
static inline bool ajiu_guest_caps_valid(const uint64_t caps[5], bool fully_nonroot)
{
    if ((caps[0] | caps[1] | caps[3]) & ~AJIU_GUEST_CAPS) return false;
    if ((caps[0] & ~caps[1]) || caps[2] || caps[4]) return false;
    return !fully_nonroot || (!caps[0] && !caps[1]);
}

static inline bool ajiu_guest_initial_caps(const uint64_t caps[5])
{
    return caps[0] == AJIU_GUEST_CAPS && caps[1] == AJIU_GUEST_CAPS &&
        !caps[2] && caps[3] == AJIU_GUEST_CAPS && !caps[4];
}

#endif
