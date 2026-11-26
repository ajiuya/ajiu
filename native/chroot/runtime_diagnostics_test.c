#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static size_t diagnostic_writes;
static int diagnostic_stdout = -1, diagnostic_stderr = -1;
static ssize_t diagnostic_write(int fd, const void *bytes, size_t length);
#undef STDOUT_FILENO
#undef STDERR_FILENO
#define STDOUT_FILENO diagnostic_stdout
#define STDERR_FILENO diagnostic_stderr
#define main chroot_runtime_production_main_not_called
#define write diagnostic_write
#include "runtime_helper.c"
#undef write
#undef main
#undef STDOUT_FILENO
#undef STDERR_FILENO

static ssize_t diagnostic_write(int fd, const void *bytes, size_t length)
{
    if (fd == diagnostic_stderr) ++diagnostic_writes;
    return write(fd, bytes, length);
}

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Native diagnostic check line %d\n", __LINE__); exit(1); } ++checks; } while (0)
static unsigned int checks;

static void check_error(int supplied_errno, int expected_errno, bool usable)
{
    int output[2], errors[2];
    CHECK(pipe2(output, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(pipe2(errors, O_CLOEXEC | O_NONBLOCK) == 0);
    diagnostic_stdout = output[1];
    diagnostic_stderr = errors[1];
    size_t before = diagnostic_writes;
    bool output_usable = usable;
    public_error(&output_usable, "prepare_failed", "app_control_authentication", supplied_errno);
    CHECK(close(output[1]) == 0 && close(errors[1]) == 0);
    diagnostic_stdout = diagnostic_stderr = -1;
    CHECK(diagnostic_writes == before + 1);
    char expected[224];
    int length = snprintf(expected, sizeof(expected), "ajiu_runtime: code=prepare_failed;stage=app_control_authentication;errno=%d\n", expected_errno);
    CHECK(length > 0 && length < 256 && (size_t)length < sizeof(expected));
    char actual[256];
    ssize_t got = read(errors[0], actual, sizeof(actual));
    CHECK(got == length && !memcmp(actual, expected, (size_t)length));
    CHECK(read(errors[0], actual, 1) == 0);
    struct ajiu_frame_header header;
    unsigned char body[256];
    int frame = ajiu_read_frame(output[0], &header, body, sizeof(body), 1000);
    if (usable) {
        CHECK(frame == 1 && header.type == AJIU_ERROR);
        char expected_body[192];
        int size = snprintf(expected_body, sizeof(expected_body), "code=prepare_failed;stage=app_control_authentication;errno=%d", expected_errno);
        CHECK(size > 0 && header.length == (uint32_t)size && !memcmp(body, expected_body, (size_t)size));
        CHECK(ajiu_read_frame(output[0], &header, body, sizeof(body), 1000) == 0);
    } else CHECK(frame == 0);
    CHECK(close(output[0]) == 0 && close(errors[0]) == 0);
}

int main(void)
{
    check_error(13, 13, true);
    check_error(4095, 4095, true);
    check_error(0, EIO, true);
    check_error(-1, EIO, true);
    check_error(4096, EIO, true);
    check_error(13, 13, false);
    printf("Runtime diagnostic checks passed: %u; real bounded pipes and fixed fields only; no production main, process, mount or device operations.\n", checks);
    return 0;
}
