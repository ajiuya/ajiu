#ifndef AJIU_ROOTFS_MANIFEST_H
#define AJIU_ROOTFS_MANIFEST_H

#include <stdint.h>

/* Private on-disk AJIM ledger (native-endian uint64_t fields), not the AJCR/AJFW wire protocol.
 * v1 remains an immutable created-inode ledger and is only accepted by legacy REMOVE.
 * New installs use v2: the outer anchors remain immutable, while the guest tree may be edited.
 * Never migrate or rewrite an existing v1 instance into v2. */
#define AJIU_MANIFEST_V1 UINT64_C(0x414a494d00000001)
#define AJIU_MANIFEST_V2 UINT64_C(0x414a494d00000002)
#define AJIU_MANIFEST_HEADER_WORDS 6U

#endif
