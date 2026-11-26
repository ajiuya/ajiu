#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "runtime_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "Protocol IO check failed at line %d: %s (errno=%d)\n", __LINE__, #condition, errno); \
        return 1; \
    } \
    ++checks; \
} while (0)

int main(void)
{
    int checks = 0, p[2];
    unsigned char bytes[AJIU_HEADER_BYTES], data[4];
    struct ajiu_frame_header h;
    CHECK(ajiu_encode_header(bytes, AJIU_RESIZE, 4) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(write(p[1], bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    CHECK(write(p[1], "\0\120\0\30", 4) == 4);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == 1);
    CHECK(h.type == AJIU_RESIZE && h.length == 4 && memcmp(data, "\0\120\0\30", 4) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, data) == 0);
    CHECK(close(p[1]) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == 0);
    CHECK(close(p[0]) == 0);
    for (size_t length = 1; length < AJIU_HEADER_BYTES; ++length) {
        CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
        CHECK(write(p[1], bytes, length) == (ssize_t)length);
        CHECK(close(p[1]) == 0);
        CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == -1 && errno == EPROTO);
        CHECK(close(p[0]) == 0);
    }
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(write(p[1], bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    CHECK(write(p[1], "\0\120", 2) == 2);
    CHECK(close(p[1]) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == -1 && errno == EPROTO);
    CHECK(close(p[0]) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(write(p[1], bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    CHECK(ajiu_read_frame(p[0], &h, data, 3, 100) == -1 && errno == EMSGSIZE);
    CHECK(close(p[0]) == 0 && close(p[1]) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 20) == -1 && errno == ETIMEDOUT);
    CHECK(write(p[1], bytes, 3) == 3);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 20) == -1 && errno == ETIMEDOUT);
    CHECK(close(p[0]) == 0 && close(p[1]) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(ajiu_encode_header(bytes, AJIU_GO, 0) == 0);
    CHECK(write(p[1], bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    CHECK(ajiu_read_frame(p[0], &h, NULL, 0, 100) == 1 && h.type == AJIU_GO && h.length == 0);
    CHECK(ajiu_read_frame(p[0], &h, NULL, 0, 0) == -1 && errno == EINVAL);
    CHECK(close(p[0]) == 0 && close(p[1]) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(ajiu_encode_header(bytes, AJIU_RESIZE, 4) == 0);
    pid_t writer = fork();
    CHECK(writer >= 0);
    if (writer == 0) {
        (void)close(p[0]);
        unsigned char fragments[AJIU_HEADER_BYTES + 4];
        memcpy(fragments, bytes, sizeof(bytes));
        memcpy(fragments + sizeof(bytes), "\0\120\0\30", 4);
        for (size_t i = 0; i < sizeof(fragments); ++i) {
            if (write(p[1], fragments + i, 1) != 1)
                _exit(1);
            struct timespec pause = { .tv_nsec = 1000000 };
            (void)nanosleep(&pause, NULL);
        }
        _exit(0);
    }
    CHECK(close(p[1]) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 1000) == 1);
    CHECK(h.type == AJIU_RESIZE && h.length == 4 && memcmp(data, "\0\120\0\30", 4) == 0);
    int status;
    CHECK(waitpid(writer, &status, 0) == writer && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == 0);
    CHECK(close(p[0]) == 0);
    CHECK(signal(SIGPIPE, SIG_IGN) != SIG_ERR);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(ajiu_write_frame(p[1], AJIU_RESIZE, (const unsigned char *)"\0\120\0\30", 4, 100) == 0);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 100) == 1 && h.type == AJIU_RESIZE && h.length == 4);
    CHECK(memcmp(data, "\0\120\0\30", 4) == 0);
    CHECK(ajiu_write_frame(p[1], AJIU_GO, NULL, 0, 100) == 0);
    CHECK(ajiu_read_frame(p[0], &h, NULL, 0, 100) == 1 && h.type == AJIU_GO);
    CHECK(ajiu_write_frame(p[1], AJIU_RESIZE, NULL, 4, 100) == -1 && errno == EINVAL);
    CHECK(ajiu_write_frame(p[1], 99, NULL, 0, 100) == -1 && errno == EPROTO);
    CHECK(ajiu_read_frame(p[0], &h, data, sizeof(data), 20) == -1 && errno == ETIMEDOUT);
    unsigned char filler[4096] = {0};
    while (write(p[1], filler, sizeof(filler)) > 0) { }
    CHECK(errno == EAGAIN);
    CHECK(ajiu_write_frame(p[1], AJIU_STOP, NULL, 0, 20) == -1 && errno == ETIMEDOUT);
    CHECK(close(p[0]) == 0);
    CHECK(ajiu_write_frame(p[1], AJIU_STOP, NULL, 0, 100) == -1 && errno == EPIPE);
    CHECK(close(p[1]) == 0);
    CHECK(pipe(p) == 0);
    CHECK(ajiu_write_frame(p[1], AJIU_STOP, NULL, 0, 100) == -1 && errno == EINVAL);
    CHECK(close(p[0]) == 0 && close(p[1]) == 0);
    CHECK(pipe2(p, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(fcntl(p[1], F_SETPIPE_SZ, 4096) == 4096);
    unsigned char payload[AJIU_MAX_DATA_BYTES];
    for (size_t i = 0; i < sizeof(payload); ++i)
        payload[i] = (unsigned char)(i % 251);
    pid_t reader = fork();
    CHECK(reader >= 0);
    if (reader == 0) {
        (void)close(p[1]);
        struct timespec pause = { .tv_nsec = 2000000 };
        (void)nanosleep(&pause, NULL);
        struct ajiu_frame_header decoded;
        unsigned char received[AJIU_MAX_DATA_BYTES];
        int result = ajiu_read_frame(p[0], &decoded, received, sizeof(received), 2000);
        _exit(result == 1 && decoded.type == AJIU_STDOUT && decoded.length == sizeof(payload) &&
              memcmp(received, payload, sizeof(payload)) == 0 ? 0 : 1);
    }
    CHECK(close(p[0]) == 0);
    CHECK(ajiu_write_frame(p[1], AJIU_STDOUT, payload, sizeof(payload), 2000) == 0);
    CHECK(close(p[1]) == 0);
    CHECK(waitpid(reader, &status, 0) == reader && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    printf("Native frame IO checks passed: %d; bounded synthetic pipes only.\n", checks);
    return 0;
}
