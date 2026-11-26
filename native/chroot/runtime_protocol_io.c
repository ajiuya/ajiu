#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "runtime_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
        return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int read_part(int fd, unsigned char *data, size_t size, int64_t deadline, int allow_eof)
{
    size_t offset = 0;
    while (offset < size) {
        int64_t now = monotonic_ms();
        if (now < 0)
            return -1;
        if (now >= deadline) {
            errno = ETIMEDOUT;
            return -1;
        }
        struct pollfd p = { .fd = fd, .events = POLLIN | POLLHUP };
        int rc = poll(&p, 1, (int)(deadline - now));
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc < 0)
            return -1;
        if (rc == 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (p.revents & POLLNVAL) {
            errno = EBADF;
            return -1;
        }
        ssize_t n = read(fd, data + offset, size - offset);
        if (n < 0 && (errno == EINTR || errno == EAGAIN))
            continue;
        if (n < 0)
            return -1;
        if (n == 0) {
            if (allow_eof && offset == 0)
                return 0;
            errno = EPROTO;
            return -1;
        }
        offset += (size_t)n;
    }
    return 1;
}

int ajiu_read_frame(int fd, struct ajiu_frame_header *header, unsigned char *body,
                    size_t capacity, unsigned int timeout_ms)
{
    if (header == NULL || timeout_ms == 0 || timeout_ms > 30000) {
        errno = EINVAL;
        return -1;
    }
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0)
        return -1;
    if (!(flags & O_NONBLOCK)) {
        errno = EINVAL;
        return -1;
    }
    int64_t now = monotonic_ms();
    if (now < 0)
        return -1;
    int64_t deadline = now + timeout_ms;
    unsigned char bytes[AJIU_HEADER_BYTES];
    int rc = read_part(fd, bytes, sizeof(bytes), deadline, 1);
    if (rc <= 0)
        return rc;
    struct ajiu_frame_header decoded;
    if (ajiu_decode_header(bytes, sizeof(bytes), &decoded) < 0)
        return -1;
    if (decoded.length > capacity || (decoded.length != 0 && body == NULL)) {
        errno = EMSGSIZE;
        return -1;
    }
    if (decoded.length && read_part(fd, body, decoded.length, deadline, 0) < 0)
        return -1;
    *header = decoded;
    return 1;
}

static int write_part(int fd, const unsigned char *data, size_t size, int64_t deadline)
{
    size_t offset = 0;
    while (offset < size) {
        int64_t now = monotonic_ms();
        if (now < 0)
            return -1;
        if (now >= deadline) {
            errno = ETIMEDOUT;
            return -1;
        }
        struct pollfd p = { .fd = fd, .events = POLLOUT };
        int rc = poll(&p, 1, (int)(deadline - now));
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc < 0)
            return -1;
        if (rc == 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (p.revents & POLLNVAL) {
            errno = EBADF;
            return -1;
        }
        ssize_t n = write(fd, data + offset, size - offset);
        if (n < 0 && (errno == EINTR || errno == EAGAIN))
            continue;
        if (n < 0)
            return -1;
        if (n == 0) {
            errno = EIO;
            return -1;
        }
        offset += (size_t)n;
    }
    return 0;
}

int ajiu_write_frame(int fd, uint16_t type, const unsigned char *body,
                     uint32_t length, unsigned int timeout_ms)
{
    if (timeout_ms == 0 || timeout_ms > 30000 || (length != 0 && body == NULL)) {
        errno = EINVAL;
        return -1;
    }
    unsigned char bytes[AJIU_HEADER_BYTES];
    if (ajiu_encode_header(bytes, type, length) < 0)
        return -1;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0)
        return -1;
    if (!(flags & O_NONBLOCK)) {
        errno = EINVAL;
        return -1;
    }
    int64_t now = monotonic_ms();
    if (now < 0)
        return -1;
    int64_t deadline = now + timeout_ms;
    if (write_part(fd, bytes, sizeof(bytes), deadline) < 0)
        return -1;
    return write_part(fd, body, length, deadline);
}
