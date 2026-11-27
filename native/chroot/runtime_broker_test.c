#define _GNU_SOURCE
#include <stdbool.h>
#include <stdint.h>
#include <time.h>

static bool fake_clock_enabled;
static unsigned int fake_clock_calls, fake_clock_jump_at;
static int64_t fake_boot_before, fake_boot_after, fake_mono_before, fake_mono_after;
static clockid_t fake_clock_seen;

static int broker_test_clock_gettime(clockid_t clock_id, struct timespec *time_value)
{
    if (!fake_clock_enabled) return clock_gettime(clock_id, time_value);
    ++fake_clock_calls;
    fake_clock_seen = clock_id;
    bool after = fake_clock_jump_at && fake_clock_calls >= fake_clock_jump_at;
    int64_t milliseconds = clock_id == CLOCK_BOOTTIME ? (after ? fake_boot_after : fake_boot_before) :
        (after ? fake_mono_after : fake_mono_before);
    time_value->tv_sec = (time_t)(milliseconds / 1000);
    time_value->tv_nsec = (long)(milliseconds % 1000) * 1000000;
    return 0;
}

#ifdef AJIU_BROKER_TEST_EXEC_FIXTURE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
static char test_fixture_path[PATH_MAX];
static long fixture_syscall(long number, ...)
{
    va_list arguments;
    va_start(arguments, number);
    long result;
    if (number == SYS_execveat) {
        int fd = va_arg(arguments, int);
        const char *path = va_arg(arguments, const char *);
        char **argv = va_arg(arguments, char **);
        char **environment = va_arg(arguments, char **);
        int flags = va_arg(arguments, int);
        struct stat pinned, named;
        if (*path || flags != AT_EMPTY_PATH || fstat(fd, &pinned) < 0 || stat(test_fixture_path, &named) < 0 ||
            pinned.st_dev != named.st_dev || pinned.st_ino != named.st_ino || !(fcntl(fd, F_GETFD) & FD_CLOEXEC)) {
            errno = EINVAL; result = -1;
        } else result = execve(test_fixture_path, argv, environment);
    } else if (number == SYS_close_range) {
        unsigned int first = va_arg(arguments, unsigned int), last = va_arg(arguments, unsigned int);
        int flags = va_arg(arguments, int);
        result = syscall(number, first, last, flags);
    } else if (number == SYS_pidfd_open) {
        pid_t pid = va_arg(arguments, pid_t);
        int flags = va_arg(arguments, int);
        result = syscall(number, pid, flags);
    } else if (number == SYS_pidfd_send_signal) {
        int fd = va_arg(arguments, int), signal_number = va_arg(arguments, int);
        void *information = va_arg(arguments, void *);
        int flags = va_arg(arguments, int);
        result = syscall(number, fd, signal_number, information, flags);
    } else { errno = ENOSYS; result = -1; }
    va_end(arguments);
    return result;
}
#define syscall fixture_syscall
#endif
#define clock_gettime broker_test_clock_gettime
#define main ajiu_broker_production_main_not_called
#include "runtime_broker.c"
#undef main
#undef clock_gettime
#ifdef AJIU_BROKER_TEST_EXEC_FIXTURE
#undef syscall
#endif

#include <grp.h>
#include <sys/mman.h>

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Broker check line %d: %s (errno=%d)\n", __LINE__, #value, errno); exit(1); } ++checks; } while (0)
static unsigned int checks, cases;

static int64_t test_deadline(void)
{
    return broker_now() + AJIU_BROKER_IO_TIMEOUT_MS;
}

static void pair_open(int sockets[2])
{
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0, sockets) == 0);
    for (size_t i = 0; i < 2; ++i) {
        CHECK((fcntl(sockets[i], F_GETFL) & O_NONBLOCK) != 0);
        CHECK((fcntl(sockets[i], F_GETFD) & FD_CLOEXEC) != 0);
    }
}

static size_t fd_count(void)
{
    DIR *d = opendir("/proc/self/fd");
    CHECK(d != NULL);
    size_t count = 0;
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) if (entry->d_name[0] != '.') ++count;
    CHECK(closedir(d) == 0);
    return count;
}

static void check_arguments(void)
{
    char nonce[65]; memset(nonce, 'a', 64); nonce[64] = '\0';
    char *argv[] = { (char *)AJIU_BROKER_NAME, (char *)"--broker", (char *)"ajiu_test-1.2", nonce,
        (char *)"10000", (char *)"123", (char *)"456", NULL };
    struct broker_arguments a;
    CHECK(parse_arguments(7, argv, &a) == 0);
    CHECK(a.uid == 10000 && a.pid == 123 && a.start == 456 && a.nonce[0] == 0xaa);
    for (int argc = 0; argc < 7; ++argc) CHECK(parse_arguments(argc, argv, &a) < 0);
    CHECK(parse_arguments(8, argv, &a) < 0);
    const char *bad_names[] = { "", "@abstract", "/data/local/tmp/name", "../a/b", "white space", "a\n", "x;id", "$(id)" };
    for (size_t i = 0; i < sizeof(bad_names) / sizeof(*bad_names); ++i) CHECK(!socket_name_valid(bad_names[i]));
    char long_name[AJIU_BROKER_MAX_SOCKET_NAME + 2U]; memset(long_name, 'x', sizeof(long_name)); long_name[sizeof(long_name) - 1] = 0;
    CHECK(!socket_name_valid(long_name)); long_name[sizeof(long_name) - 2] = 0; CHECK(socket_name_valid(long_name));
    const char *bad_numbers[] = { "", "0", "-1", "+1", "01", "1x", "18446744073709551616", "99999999999999999999999999" };
    for (size_t i = 0; i < sizeof(bad_numbers) / sizeof(*bad_numbers); ++i) {
        uint64_t value = 17;
        CHECK(!decimal_value(bad_numbers[i], UINT64_MAX, &value));
        CHECK(!decimal_value(bad_numbers[i], INT_MAX, &value));
    }
    nonce[63] = 'A'; CHECK(parse_arguments(7, argv, &a) < 0); nonce[63] = 'a';
    argv[4] = (char *)"9999"; CHECK(parse_arguments(7, argv, &a) < 0); argv[4] = (char *)"10000";
    argv[5] = (char *)"1"; CHECK(parse_arguments(7, argv, &a) < 0); argv[5] = (char *)"123";
    argv[1] = (char *)"--exec"; CHECK(parse_arguments(7, argv, &a) < 0);
    ++cases;
}

static void make_start(unsigned char *b, uint64_t id, uint32_t operation)
{
    memset(b, 0, AJIU_BROKER_START_SIZE); put64(b, id); put32(b + 8, operation);
    if (operation != AJIU_BROKER_RUNTIME) memset(b + 16, 'a', AJIU_BROKER_INSTANCE_SIZE);
    if (operation == AJIU_BROKER_REMOVE || operation == AJIU_BROKER_RECOVER_REMOVE) {
        put64(b + 40, 1234); put64(b + 48, 5678);
    }
}

static void rejected_request(uint16_t type, const unsigned char *b, uint32_t length, uint64_t next)
{
    struct ajiu_broker_header h = { .type = type, .length = length };
    struct ajiu_broker_request r;
    uint64_t saved = next;
    CHECK(parse_request(&h, b, &next, &r) < 0);
    CHECK(next == saved);
    ++cases;
}

static void check_requests(void)
{
    unsigned char b[AJIU_BROKER_MAX_PAYLOAD] = { 0 };
    struct ajiu_broker_header h = { .type = AJIU_BROKER_START, .length = AJIU_BROKER_START_SIZE };
    struct ajiu_broker_request r;
    uint64_t next = 1;
    for (uint32_t op = AJIU_BROKER_RUNTIME; op <= AJIU_BROKER_RECOVER_REMOVE; ++op) {
        make_start(b, next, op);
        CHECK(parse_request(&h, b, &next, &r) == 0 && r.id == next - 1 && r.operation == op);
        CHECK(r.instance[AJIU_BROKER_INSTANCE_SIZE] == 0);
    }
    make_start(b, 1, AJIU_BROKER_RUNTIME);
    rejected_request(AJIU_BROKER_START, b, sizeof(b), 1);
    for (uint32_t length = 0; length < AJIU_BROKER_START_SIZE; ++length) rejected_request(AJIU_BROKER_START, b, length, 1);
    rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 2);
    put64(b, 0); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
    put64(b, 2); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
    put64(b, UINT64_MAX); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, UINT64_MAX);
    put64(b, (uint64_t)INT64_MAX + 1); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, (uint64_t)INT64_MAX + 1);
    put64(b, INT64_MAX); next = INT64_MAX; CHECK(parse_request(&h, b, &next, &r) == 0 && next == (uint64_t)INT64_MAX + 1);
    make_start(b, 1, AJIU_BROKER_RUNTIME);
    for (size_t offset = 12; offset < AJIU_BROKER_START_SIZE; ++offset) {
        b[offset] = 1; rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1); b[offset] = 0;
    }
    const uint32_t operations[] = { 0, 5, UINT32_MAX, 0x65786563 };
    for (size_t i = 0; i < sizeof(operations) / sizeof(*operations); ++i) {
        make_start(b, 1, AJIU_BROKER_RECOVER_REMOVE);
        put32(b + 8, operations[i]); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
    }
    const unsigned char bad_instance[] = { 0, 'A', 'g', '/', '\\', ';', ' ', '\n', '.', '$', 255 };
    for (uint32_t op = AJIU_BROKER_INSTALL; op <= AJIU_BROKER_RECOVER_REMOVE; ++op) {
        make_start(b, 1, op);
        for (size_t i = 0; i < sizeof(bad_instance); ++i) {
            for (size_t j = 0; j < AJIU_BROKER_INSTANCE_SIZE; ++j) {
                b[16 + j] = bad_instance[i]; rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1); b[16 + j] = 'a';
            }
        }
    }
    make_start(b, 1, AJIU_BROKER_INSTALL);
    put64(b + 40, 1); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1); put64(b + 40, 0);
    put64(b + 48, 1); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
    for (uint32_t op = AJIU_BROKER_REMOVE; op <= AJIU_BROKER_RECOVER_REMOVE; ++op) {
        make_start(b, 1, op); put64(b + 40, 0); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
        make_start(b, 1, op); put64(b + 48, 0); rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
        make_start(b, 1, op); put64(b + 40, 0); put64(b + 48, 0);
        rejected_request(AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, 1);
    }
    memset(b, 0, sizeof(b)); put64(b, 2); put64(b + 8, 1); next = 2;
    h.type = AJIU_BROKER_STOP; h.length = AJIU_BROKER_STOP_SIZE;
    CHECK(parse_request(&h, b, &next, &r) == 0 && next == 3 && r.target == 1);
    put64(b + 8, 0); rejected_request(AJIU_BROKER_STOP, b, AJIU_BROKER_STOP_SIZE, 2);
    put64(b + 8, 2); rejected_request(AJIU_BROKER_STOP, b, AJIU_BROKER_STOP_SIZE, 2);
    put64(b, 1); next = 1; h.type = AJIU_BROKER_SHUTDOWN; h.length = AJIU_BROKER_SHUTDOWN_SIZE;
    CHECK(parse_request(&h, b, &next, &r) == 0 && next == 2);
    for (uint16_t type = AJIU_BROKER_HELLO; type <= AJIU_BROKER_BYE + 1; ++type)
        if (type != AJIU_BROKER_START && type != AJIU_BROKER_STOP && type != AJIU_BROKER_SHUTDOWN)
            rejected_request(type, b, 8, 1);
    ++cases;
}

static void send_raw_fds(int socket_fd, const unsigned char *data, size_t length, size_t count)
{
    CHECK(count <= 64);
    int fds[64];
    for (size_t i = 0; i < count; ++i) { fds[i] = open("/dev/null", O_RDWR | O_CLOEXEC); CHECK(fds[i] >= 0); }
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int) * 64U)]; } control;
    memset(&control, 0, sizeof(control));
    struct iovec iov = { .iov_base = (void *)data, .iov_len = length };
    struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1 };
    if (count) {
        message.msg_control = control.bytes; message.msg_controllen = CMSG_SPACE(sizeof(int) * count);
        struct cmsghdr *c = CMSG_FIRSTHDR(&message);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int) * count);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * count);
    }
    CHECK(sendmsg(socket_fd, &message, MSG_NOSIGNAL) == (ssize_t)length);
    close_fds(fds, count);
}

static void check_fd_rejection(size_t count, size_t expected, bool wrong_offset)
{
    int sockets[2]; pair_open(sockets);
    size_t before = fd_count();
    unsigned char wire[AJIU_BROKER_HEADER_SIZE + AJIU_BROKER_STARTED_SIZE] = { 0 };
    CHECK(encode_header(wire, AJIU_BROKER_STARTED, AJIU_BROKER_STARTED_SIZE) == 0);
    if (wrong_offset) {
        CHECK(send(sockets[1], wire, 1, MSG_NOSIGNAL) == 1);
        send_raw_fds(sockets[1], wire + 1, sizeof(wire) - 1, count);
    } else send_raw_fds(sockets[1], wire, sizeof(wire), count);
    struct ajiu_broker_header h;
    unsigned char body[AJIU_BROKER_MAX_PAYLOAD]; int received[4] = { -1, -1, -1, -1 };
    CHECK(receive_frame(sockets[0], &h, body, received, expected, test_deadline()) < 0);
    for (size_t i = 0; i < expected; ++i) CHECK(received[i] == -1);
    CHECK(fd_count() == before);
    close_fds(sockets, 2);
    ++cases;
}

static void check_wire(void)
{
    for (size_t count = 0; count <= 8; ++count) if (count != 4) check_fd_rejection(count, 4, false);
    check_fd_rejection(64, 4, false);
    check_fd_rejection(4, 0, false);
    check_fd_rejection(4, 0, true);
    unsigned char wire[AJIU_BROKER_HEADER_SIZE + AJIU_BROKER_START_SIZE] = { 0 };
    CHECK(encode_header(wire, AJIU_BROKER_START, AJIU_BROKER_START_SIZE) == 0);
    make_start(wire + AJIU_BROKER_HEADER_SIZE, 1, AJIU_BROKER_RUNTIME);
    for (size_t cut = 0; cut < sizeof(wire); ++cut) {
        int sockets[2]; pair_open(sockets);
        if (cut) CHECK(send(sockets[1], wire, cut, MSG_NOSIGNAL) == (ssize_t)cut);
        CHECK(shutdown(sockets[1], SHUT_WR) == 0);
        struct ajiu_broker_header h; unsigned char body[AJIU_BROKER_MAX_PAYLOAD];
        CHECK(receive_frame(sockets[0], &h, body, NULL, 0, test_deadline()) < 0);
        close_fds(sockets, 2); ++cases;
    }
    for (size_t i = 0; i < AJIU_BROKER_HEADER_SIZE; ++i) {
        unsigned char bad[AJIU_BROKER_HEADER_SIZE]; memcpy(bad, wire, sizeof(bad));
        bad[i] ^= 0x80;
        struct ajiu_broker_header h;
        if (i == 10 || i == 11) continue;
        CHECK(decode_header(bad, &h) < 0);
    }
    int sockets[2]; pair_open(sockets);
    CHECK(receive_frame(sockets[0], &(struct ajiu_broker_header){0}, wire, NULL, 0, broker_now()) < 0 && errno == ETIMEDOUT);
    close_fds(sockets, 2);
    CHECK(encode_header(wire, 0, 0) < 0);
    CHECK(encode_header(wire, AJIU_BROKER_START, AJIU_BROKER_MAX_PAYLOAD + 1) < 0);
    ++cases;
}

static struct broker_arguments self_arguments(void)
{
    struct broker_arguments a = { .uid = (uint32_t)getuid(), .pid = (uint32_t)getpid() };
    CHECK(process_start(a.pid, &a.start) == 0 && a.start != 0);
    for (size_t i = 0; i < sizeof(a.nonce); ++i) a.nonce[i] = (unsigned char)i;
    return a;
}

static void check_peer_and_hello(void)
{
    int sockets[2]; pair_open(sockets);
    struct broker_arguments a = self_arguments();
    int pidfd = pidfd_open_bound(getpid()); CHECK(pidfd >= 0 && pidfd_alive(pidfd));
    CHECK(validate_app(sockets[0], &a, pidfd) == 0);
    struct ucred peer = { .pid = (pid_t)a.pid, .uid = (uid_t)a.uid, .gid = (gid_t)a.uid };
    CHECK(peer_matches(&peer, &a));
    ++peer.pid; CHECK(!peer_matches(&peer, &a)); --peer.pid;
    ++peer.uid; CHECK(!peer_matches(&peer, &a)); --peer.uid;
    ++peer.gid; CHECK(!peer_matches(&peer, &a));
    ++a.pid; CHECK(validate_app(sockets[0], &a, pidfd) < 0); --a.pid;
    ++a.uid; CHECK(validate_app(sockets[0], &a, pidfd) < 0); --a.uid;
    ++a.start; CHECK(validate_app(sockets[0], &a, pidfd) < 0); --a.start;
    CHECK(validate_app(sockets[0], &a, -1) < 0);
    close_fds(sockets, 2);
    for (unsigned int altered = 0; altered < 5; ++altered) {
        pair_open(sockets);
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            (void)close(sockets[0]);
            struct ajiu_broker_header h; unsigned char b[AJIU_BROKER_MAX_PAYLOAD];
            if (receive_frame(sockets[1], &h, b, NULL, 0, test_deadline()) < 0 || h.type != AJIU_BROKER_HELLO || h.length != AJIU_BROKER_HELLO_SIZE) _exit(91);
            if (altered) b[altered == 1 ? 0 : altered == 2 ? 36 : altered == 3 ? 40 : 48] ^= 1;
            if (send_frame(sockets[1], AJIU_BROKER_HELLO_ACK, b, h.length, NULL, 0, test_deadline()) < 0) _exit(92);
            _exit(0);
        }
        CHECK(close(sockets[1]) == 0); sockets[1] = -1;
        CHECK((handshake(sockets[0], &a, pidfd) == 0) == (altered == 0));
        int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        close_fds(sockets, 2); ++cases;
    }
    CHECK(close(pidfd) == 0);
}

static void read_pipe_exact(int fd, unsigned char *bytes, size_t length)
{
    size_t done = 0;
    while (done < length) {
        CHECK(broker_poll(fd, POLLIN, test_deadline()) == 0);
        ssize_t n = read(fd, bytes + done, length - done);
        CHECK(n > 0); done += (size_t)n;
    }
}

static uint16_t exit_record(int fd, uint32_t *value)
{
    unsigned char b[AJIU_BROKER_EXIT_SIZE];
    size_t done = 0;
    while (done < sizeof(b)) {
        struct pollfd p = { .fd = fd, .events = POLLIN };
        if (poll(&p, 1, AJIU_BROKER_IO_TIMEOUT_MS) <= 0) return AJIU_BROKER_UNKNOWN;
        ssize_t n = read(fd, b + done, sizeof(b) - done);
        if (n <= 0) return AJIU_BROKER_UNKNOWN;
        done += (size_t)n;
    }
    uint16_t outcome = get16(b + 6); *value = get32(b + 8);
    if (get32(b) != AJIU_BROKER_EXIT_MAGIC || get16(b + 4) != AJIU_BROKER_VERSION || get32(b + 12) ||
        outcome < AJIU_BROKER_EXITED || outcome > AJIU_BROKER_UNKNOWN ||
        (outcome == AJIU_BROKER_EXITED && *value > 255) ||
        (outcome == AJIU_BROKER_SIGNAL && (!*value || *value >= NSIG)) ||
        (outcome == AJIU_BROKER_UNKNOWN && *value)) return AJIU_BROKER_UNKNOWN;
    unsigned char extra;
    struct pollfd p = { .fd = fd, .events = POLLIN };
    if (poll(&p, 1, AJIU_BROKER_IO_TIMEOUT_MS) <= 0 || read(fd, &extra, 1) != 0) return AJIU_BROKER_UNKNOWN;
    return outcome;
}

static void check_status_unknown(void)
{
    for (size_t cut = 0; cut < AJIU_BROKER_EXIT_SIZE; ++cut) {
        int p[2]; CHECK(pipe2(p, O_CLOEXEC) == 0);
        unsigned char b[AJIU_BROKER_EXIT_SIZE] = { 0 };
        put32(b, AJIU_BROKER_EXIT_MAGIC); put16(b + 4, AJIU_BROKER_VERSION); put16(b + 6, AJIU_BROKER_EXITED);
        if (cut) CHECK(write(p[1], b, cut) == (ssize_t)cut);
        CHECK(close(p[1]) == 0); p[1] = -1;
        uint32_t value = UINT32_MAX; CHECK(exit_record(p[0], &value) == AJIU_BROKER_UNKNOWN);
        close_fds(p, 2); ++cases;
    }
    for (size_t offset = 0; offset < AJIU_BROKER_EXIT_SIZE; ++offset) {
        int p[2]; CHECK(pipe2(p, O_CLOEXEC) == 0);
        unsigned char b[AJIU_BROKER_EXIT_SIZE] = { 0 };
        put32(b, AJIU_BROKER_EXIT_MAGIC); put16(b + 4, AJIU_BROKER_VERSION); put16(b + 6, AJIU_BROKER_EXITED);
        b[offset] ^= 0x80;
        CHECK(write(p[1], b, sizeof(b)) == (ssize_t)sizeof(b)); CHECK(close(p[1]) == 0); p[1] = -1;
        uint32_t value = UINT32_MAX;
        if (offset != 11) CHECK(exit_record(p[0], &value) == AJIU_BROKER_UNKNOWN);
        else CHECK(exit_record(p[0], &value) == AJIU_BROKER_EXITED && value == 128);
        close_fds(p, 2); ++cases;
    }
}

static void initialize_jobs(struct broker_job *jobs)
{
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) jobs[i] = (struct broker_job){ .status = -1, .pidfd = -1 };
}

static void await_reap(struct broker_job *jobs, size_t expected_alive)
{
    int64_t end = test_deadline();
    while (reap_jobs(jobs) != expected_alive) {
        CHECK(broker_now() < end);
        struct pollfd p[AJIU_BROKER_MAX_JOBS]; nfds_t n = 0;
        for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i)
            if (jobs[i].id && jobs[i].pidfd >= 0) p[n++] = (struct pollfd){ .fd = jobs[i].pidfd, .events = POLLIN };
        CHECK(poll(p, n, 100) >= 0);
    }
}

static pid_t take_started(int fd, uint64_t id, int *fds)
{
    unsigned char body[AJIU_BROKER_MAX_PAYLOAD]; struct ajiu_broker_header h;
    CHECK(receive_frame(fd, &h, body, fds, AJIU_BROKER_FD_COUNT, test_deadline()) == 0);
    CHECK(h.type == AJIU_BROKER_STARTED && h.length == AJIU_BROKER_STARTED_SIZE);
    CHECK(get64(body) == id && get32(body + 8) > 1 && get32(body + 12) == 0);
    struct stat identities[AJIU_BROKER_FD_COUNT];
    for (size_t i = 0; i < AJIU_BROKER_FD_COUNT; ++i) {
        CHECK((fcntl(fds[i], F_GETFD) & FD_CLOEXEC) != 0);
        CHECK(fstat(fds[i], identities + i) == 0 && S_ISFIFO(identities[i].st_mode));
        CHECK((fcntl(fds[i], F_GETFL) & O_ACCMODE) == (i ? O_RDONLY : O_WRONLY));
        for (size_t j = 0; j < i; ++j)
            CHECK(identities[i].st_dev != identities[j].st_dev || identities[i].st_ino != identities[j].st_ino);
    }
    CHECK((fcntl(fds[AJIU_BROKER_STATUS], F_GETFL) & O_NONBLOCK) == 0);
    return (pid_t)get32(body + 8);
}

static struct broker_executable fixture_executable(void)
{
    struct broker_executable e = { .fd = open("/proc/self/exe", O_RDONLY | O_CLOEXEC) };
    CHECK(e.fd >= 0 && fstat(e.fd, &e.identity) == 0);
    CHECK(executable_current(&e));
    return e;
}

static void check_spawn(void)
{
    size_t before = fd_count();
    struct broker_executable e = fixture_executable();
    int inherited_high = fcntl(e.fd, F_DUPFD, 4096); CHECK(inherited_high >= 4096);
    struct broker_job jobs[AJIU_BROKER_MAX_JOBS]; initialize_jobs(jobs);
    int sockets[2]; pair_open(sockets);
    struct ajiu_broker_request r = { .id = 1, .operation = AJIU_BROKER_RUNTIME };
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    int fds[4]; pid_t child = take_started(sockets[1], 1, fds);
    CHECK(write(fds[0], "wire-data", 9) == 9); CHECK(close(fds[0]) == 0); fds[0] = -1;
    unsigned char output[9]; read_pipe_exact(fds[1], output, sizeof(output)); CHECK(!memcmp(output, "wire-data", sizeof(output)));
    await_reap(jobs, 0);
    unsigned char errors[14]; read_pipe_exact(fds[2], errors, sizeof(errors)); CHECK(!memcmp(errors, "fixture-stderr", sizeof(errors)));
    unsigned char extra; CHECK(read(fds[1], &extra, 1) == 0 && read(fds[2], &extra, 1) == 0);
    uint32_t value; CHECK(exit_record(fds[3], &value) == AJIU_BROKER_EXITED && value == 37);
    int status; CHECK(waitpid(child, &status, WNOHANG) < 0 && errno == ECHILD);
    close_fds(fds, 4);
    r.id = 2; r.operation = AJIU_BROKER_INSTALL; memset(r.instance, 'a', AJIU_BROKER_INSTANCE_SIZE);
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    int first[4]; (void)take_started(sockets[1], 2, first);
    r.id = 3; r.operation = AJIU_BROKER_RUNTIME;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    int second[4]; (void)take_started(sockets[1], 3, second);
    CHECK(close(first[0]) == 0); first[0] = -1;
    await_reap(jobs, 1);
    struct pollfd eof[2] = { { .fd = first[1], .events = POLLIN }, { .fd = first[2], .events = POLLIN } };
    CHECK(poll(eof, 2, 100) == 2);
    CHECK(read(first[1], &extra, 1) == 0 && read(first[2], &extra, 1) == 0);
    CHECK(exit_record(first[3], &value) == AJIU_BROKER_EXITED && value == 23);
    CHECK(jobs[1].id == 3 && pidfd_alive(jobs[1].pidfd));
    struct ajiu_broker_header h = { .type = AJIU_BROKER_STOP, .length = AJIU_BROKER_STOP_SIZE };
    struct ajiu_broker_request stop = { .id = 4, .target = 3 };
    CHECK(serve_request(sockets[0], &h, &stop, jobs, &e, &e) == 0);
    unsigned char body[AJIU_BROKER_MAX_PAYLOAD];
    CHECK(receive_frame(sockets[1], &h, body, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_STOPPED);
    CHECK(get64(body) == 4 && get64(body + 8) == 3);
    await_reap(jobs, 0);
    CHECK(exit_record(second[3], &value) == AJIU_BROKER_SIGNAL && value == SIGTERM);
    close_fds(first, 4); close_fds(second, 4);
    r.id = 5; r.operation = AJIU_BROKER_REMOVE; r.device = 1234; r.inode = 5678;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0); (void)take_started(sockets[1], 5, fds);
    await_reap(jobs, 0); CHECK(exit_record(fds[3], &value) == AJIU_BROKER_EXITED && value == 24); close_fds(fds, 4);
    r.id = 6; r.operation = AJIU_BROKER_RECOVER_REMOVE;
    struct broker_executable unavailable_runtime = { .fd = -1 };
    CHECK(spawn_job(sockets[0], &r, jobs, &unavailable_runtime, &e) == 0); (void)take_started(sockets[1], 6, fds);
    await_reap(jobs, 0); CHECK(exit_record(fds[3], &value) == AJIU_BROKER_EXITED && value == 25); close_fds(fds, 4);
    r.id = 7; r.operation = AJIU_BROKER_RUNTIME;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0); (void)take_started(sockets[1], 7, fds);
    stop_jobs(jobs); CHECK(exit_record(fds[3], &value) == AJIU_BROKER_SIGNAL && value == SIGTERM); close_fds(fds, 4);
    close_fds(sockets, 2); CHECK(close(e.fd) == 0); CHECK(close(inherited_high) == 0);
    CHECK(fd_count() == before); ++cases;
}

static void check_failures(void)
{
    struct broker_job jobs[AJIU_BROKER_MAX_JOBS]; initialize_jobs(jobs);
    struct broker_executable e = fixture_executable();
    int sockets[2]; pair_open(sockets);
    struct ajiu_broker_request r = { .id = 1, .operation = AJIU_BROKER_RUNTIME };
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) jobs[i].id = i + 1;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    struct ajiu_broker_header h; unsigned char body[AJIU_BROKER_MAX_PAYLOAD];
    CHECK(receive_frame(sockets[1], &h, body, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_FAILED);
    CHECK(get32(body + 8) == AJIU_BROKER_FAIL_CAPACITY && get32(body + 12) == EBUSY);
    initialize_jobs(jobs); ++e.identity.st_ino;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    CHECK(receive_frame(sockets[1], &h, body, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_FAILED);
    CHECK(get32(body + 8) == AJIU_BROKER_FAIL_IDENTITY); --e.identity.st_ino;
    int status; CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD);
    r.id = 2; r.target = 1; h.type = AJIU_BROKER_STOP;
    CHECK(serve_request(sockets[0], &h, &r, jobs, &e, &e) == 0);
    CHECK(receive_frame(sockets[1], &h, body, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_FAILED);
    CHECK(get32(body + 8) == AJIU_BROKER_FAIL_TARGET && get32(body + 12) == ENOENT);
    CHECK(shutdown(sockets[1], SHUT_RDWR) == 0); r.operation = AJIU_BROKER_RUNTIME; r.id = 3;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) < 0);
    CHECK(jobs[0].id == 3 && jobs[0].stopped);
    stop_jobs(jobs); CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD);
    close_fds(sockets, 2); CHECK(close(e.fd) == 0); ++cases;
}

static pid_t loop_fixture(int fd, int other_fd, const struct broker_arguments *a, const struct broker_executable *e)
{
    int pidfd = pidfd_open_bound((pid_t)a->pid); CHECK(pidfd >= 0);
    pid_t broker = fork(); CHECK(broker >= 0);
    if (!broker) {
        (void)close(other_fd);
        int rc = broker_loop(fd, pidfd, a, e, e);
        int status;
        if (waitpid(-1, &status, WNOHANG) != -1 || errno != ECHILD) _exit(94);
        _exit(rc > 0 ? 95 : 0);
    }
    CHECK(close(pidfd) == 0);
    return broker;
}

static void wait_fixture(pid_t child)
{
    int pidfd = pidfd_open_bound(child); CHECK(pidfd >= 0);
    struct pollfd p = { .fd = pidfd, .events = POLLIN };
    CHECK(poll(&p, 1, AJIU_BROKER_IO_TIMEOUT_MS + AJIU_BROKER_SHUTDOWN_TIMEOUT_MS) > 0);
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(close(pidfd) == 0);
}

static void check_control_loop(void)
{
    size_t before = fd_count();
    struct broker_executable e = fixture_executable();
    struct broker_arguments a = self_arguments();
    for (unsigned int mode = 0; mode < 8; ++mode) {
        int sockets[2]; pair_open(sockets);
        pid_t broker = loop_fixture(sockets[0], sockets[1], &a, &e);
        CHECK(close(sockets[0]) == 0); sockets[0] = -1;
        unsigned char body[AJIU_BROKER_MAX_PAYLOAD]; make_start(body, 1, AJIU_BROKER_RUNTIME);
        int fds[4] = { -1, -1, -1, -1 };
        if (mode >= 4) {
            CHECK(send_frame(sockets[1], AJIU_BROKER_START, body, AJIU_BROKER_START_SIZE, NULL, 0, test_deadline()) == 0);
            (void)take_started(sockets[1], 1, fds);
        }
        if (mode == 0) put32(body + 8, 4);
        else if (mode == 1) put64(body, 2);
        else if (mode == 2) body[16] = '/';
        else if (mode == 3) put64(body, UINT64_MAX);
        if (mode <= 4) {
            CHECK(send_frame(sockets[1], AJIU_BROKER_START, body, AJIU_BROKER_START_SIZE, NULL, 0, test_deadline()) == 0);
        } else if (mode == 5) {
            CHECK(shutdown(sockets[1], SHUT_WR) == 0);
        } else if (mode == 6) {
            put64(body, 2);
            CHECK(send_frame(sockets[1], AJIU_BROKER_SHUTDOWN, body, AJIU_BROKER_SHUTDOWN_SIZE, NULL, 0, test_deadline()) == 0);
            struct ajiu_broker_header h;
            CHECK(receive_frame(sockets[1], &h, body, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_BYE && h.length == 8 && get64(body) == 2);
        } else {
            unsigned char malformed[AJIU_BROKER_HEADER_SIZE];
            CHECK(encode_header(malformed, AJIU_BROKER_START, AJIU_BROKER_START_SIZE) == 0);
            malformed[0] = 'X'; send_raw_fds(sockets[1], malformed, sizeof(malformed), 4);
        }
        wait_fixture(broker);
        unsigned char byte;
        ssize_t closed = recv(sockets[1], &byte, 1, MSG_DONTWAIT);
        CHECK(closed == 0 || (closed < 0 && errno == ECONNRESET));
        if (mode >= 4) { uint32_t value; CHECK(exit_record(fds[3], &value) == AJIU_BROKER_SIGNAL && value == SIGTERM); }
        close_fds(fds, 4); close_fds(sockets, 2); ++cases;
    }
    CHECK(close(e.fd) == 0 && fd_count() == before);
}

static void check_actual_capacity(void)
{
    size_t before = fd_count();
    struct broker_executable e = fixture_executable();
    struct broker_job jobs[AJIU_BROKER_MAX_JOBS]; initialize_jobs(jobs);
    int sockets[2]; pair_open(sockets);
    int descriptors[AJIU_BROKER_MAX_JOBS][4];
    struct ajiu_broker_request r = { .operation = AJIU_BROKER_RUNTIME };
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) {
        r.id = i + 1;
        CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
        (void)take_started(sockets[1], r.id, descriptors[i]);
    }
    r.id = AJIU_BROKER_MAX_JOBS + 1;
    CHECK(spawn_job(sockets[0], &r, jobs, &e, &e) == 0);
    struct ajiu_broker_header h; unsigned char b[AJIU_BROKER_MAX_PAYLOAD];
    CHECK(receive_frame(sockets[1], &h, b, NULL, 0, test_deadline()) == 0 && h.type == AJIU_BROKER_FAILED);
    CHECK(get64(b) == r.id && get32(b + 8) == AJIU_BROKER_FAIL_CAPACITY);
    stop_jobs(jobs);
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) {
        uint32_t value;
        CHECK(exit_record(descriptors[i][3], &value) == AJIU_BROKER_SIGNAL && value == SIGTERM);
        close_fds(descriptors[i], 4);
    }
    int status; CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD);
    close_fds(sockets, 2); CHECK(close(e.fd) == 0 && fd_count() == before); ++cases;
}

static void check_app_death(void)
{
    size_t before = fd_count();
    struct broker_executable e = fixture_executable();
    int relay[2]; pair_open(relay);
    pid_t app = fork(); CHECK(app >= 0);
    if (!app) {
        (void)close(relay[0]);
        int control[2]; pair_open(control);
        unsigned char b[AJIU_BROKER_MAX_PAYLOAD] = { 0 };
        if (send_frame(relay[1], AJIU_BROKER_BYE, NULL, 0, control, 1, test_deadline()) < 0) _exit(96);
        (void)close(control[0]);
        unsigned char go;
        if (receive_exact(relay[1], &go, 1, test_deadline()) < 0) _exit(97);
        make_start(b, 1, AJIU_BROKER_RUNTIME);
        if (send_frame(control[1], AJIU_BROKER_START, b, AJIU_BROKER_START_SIZE, NULL, 0, test_deadline()) < 0) _exit(98);
        int fds[4]; (void)take_started(control[1], 1, fds);
        if (send_frame(relay[1], AJIU_BROKER_STARTED, NULL, 0, fds, 4, test_deadline()) < 0 ||
            send_frame(relay[1], AJIU_BROKER_BYE, NULL, 0, control + 1, 1, test_deadline()) < 0 ||
            receive_exact(relay[1], &go, 1, test_deadline()) < 0) _exit(99);
        _exit(0);
    }
    CHECK(close(relay[1]) == 0); relay[1] = -1;
    struct broker_arguments a = { .uid = (uint32_t)getuid(), .pid = (uint32_t)app };
    CHECK(process_start(a.pid, &a.start) == 0);
    struct ajiu_broker_header h; unsigned char b[AJIU_BROKER_MAX_PAYLOAD]; int server[1];
    CHECK(receive_frame(relay[0], &h, b, server, 1, test_deadline()) == 0);
    int app_pidfd = pidfd_open_bound(app); CHECK(app_pidfd >= 0);
    pid_t broker = fork(); CHECK(broker >= 0);
    if (!broker) {
        (void)close(relay[0]);
        (void)broker_loop(server[0], app_pidfd, &a, &e, &e);
        int status;
        if (waitpid(-1, &status, WNOHANG) != -1 || errno != ECHILD) _exit(100);
        _exit(0);
    }
    CHECK(close(server[0]) == 0 && close(app_pidfd) == 0);
    CHECK(send(relay[0], "g", 1, MSG_NOSIGNAL) == 1);
    int fds[4], inherited_control[1];
    CHECK(receive_frame(relay[0], &h, b, fds, 4, test_deadline()) == 0 && h.type == AJIU_BROKER_STARTED);
    CHECK(receive_frame(relay[0], &h, b, inherited_control, 1, test_deadline()) == 0);
    CHECK(send(relay[0], "x", 1, MSG_NOSIGNAL) == 1);
    wait_fixture(app);
    wait_fixture(broker);
    uint32_t value; CHECK(exit_record(fds[3], &value) == AJIU_BROKER_SIGNAL && value == SIGTERM);
    CHECK(send(inherited_control[0], "x", 1, MSG_NOSIGNAL) < 0 && errno == EPIPE);
    close_fds(inherited_control, 1); close_fds(fds, 4); close_fds(relay, 2);
    CHECK(close(e.fd) == 0 && fd_count() == before); ++cases;
}

static void enable_fake_clock(unsigned int jump_at, int64_t boot_gap, int64_t mono_gap)
{
    fake_clock_calls = 0; fake_clock_jump_at = jump_at;
    fake_boot_before = fake_mono_before = 100000;
    fake_boot_after = fake_boot_before + boot_gap;
    fake_mono_after = fake_mono_before + mono_gap;
    fake_clock_enabled = true;
}

static void check_suspend_clock(void)
{
    enable_fake_clock(2, AJIU_BROKER_LIFETIME_MS + 1, 1000);
    int64_t expires = broker_now() + AJIU_BROKER_LIFETIME_MS;
    CHECK(fake_clock_seen == CLOCK_BOOTTIME);
    CHECK(broker_now() >= expires && fake_mono_after < expires);
    fake_clock_enabled = false;
    ++cases;
    int sockets[2]; pair_open(sockets);
    unsigned char body[AJIU_BROKER_SHUTDOWN_SIZE] = { 0 };
    put64(body, 1);
    enable_fake_clock(1, AJIU_BROKER_IO_TIMEOUT_MS + 1, 1000);
    int64_t deadline = fake_boot_before + AJIU_BROKER_IO_TIMEOUT_MS;
    CHECK(send_frame(sockets[0], AJIU_BROKER_SHUTDOWN, body, sizeof(body), NULL, 0, deadline) < 0 && errno == ETIMEDOUT);
    unsigned char byte;
    CHECK(recv(sockets[1], &byte, 1, MSG_DONTWAIT) < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
    CHECK(send(sockets[1], "x", 1, MSG_NOSIGNAL) == 1);
    CHECK(receive_piece(sockets[0], &byte, 1, NULL, 0, deadline) < 0 && errno == ETIMEDOUT);
    CHECK(recv(sockets[0], &byte, 1, MSG_DONTWAIT | MSG_PEEK) == 1 && byte == 'x');
    enable_fake_clock(2, AJIU_BROKER_IO_TIMEOUT_MS + 1, 1000);
    CHECK(broker_poll(sockets[0], POLLIN, deadline) < 0 && errno == ETIMEDOUT);
    CHECK(fake_clock_calls == 2 && fake_clock_seen == CLOCK_BOOTTIME);
    fake_clock_enabled = false;
    close_fds(sockets, 2); ++cases;
    size_t before = fd_count();
    struct broker_executable e = fixture_executable();
    struct broker_arguments a = self_arguments();
    const unsigned int jump_calls[] = { 2, 7 };
    for (size_t i = 0; i < sizeof(jump_calls) / sizeof(*jump_calls); ++i) {
        pair_open(sockets);
        unsigned char request[AJIU_BROKER_START_SIZE]; make_start(request, 1, AJIU_BROKER_RUNTIME);
        CHECK(send_frame(sockets[1], AJIU_BROKER_START, request, sizeof(request), NULL, 0, test_deadline()) == 0);
        enable_fake_clock(jump_calls[i], AJIU_BROKER_LIFETIME_MS + 1, 1000);
        pid_t broker = loop_fixture(sockets[0], sockets[1], &a, &e);
        fake_clock_enabled = false;
        CHECK(close(sockets[0]) == 0); sockets[0] = -1;
        wait_fixture(broker);
        ssize_t closed = recv(sockets[1], &byte, 1, MSG_DONTWAIT);
        CHECK(closed == 0 || (closed < 0 && errno == ECONNRESET));
        close_fds(sockets, 2); ++cases;
    }
    CHECK(close(e.fd) == 0 && fd_count() == before);
    puts("Broker clock tests: synthetic suspend gaps only; no device suspend exercised");
}

static void check_bounded_unknown(void)
{
    size_t before = fd_count();
    int status_pipe[2], ready[2];
    CHECK(pipe2(status_pipe, O_CLOEXEC) == 0 && pipe2(ready, O_CLOEXEC) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        (void)close(status_pipe[0]); (void)close(status_pipe[1]); (void)close(ready[0]);
        if (signal(SIGTERM, SIG_IGN) == SIG_ERR || write(ready[1], "r", 1) != 1) _exit(101);
        (void)close(ready[1]);
        for (;;) pause();
    }
    CHECK(close(ready[1]) == 0); ready[1] = -1;
    unsigned char byte; read_pipe_exact(ready[0], &byte, 1); close_fds(ready, 2);
    struct broker_job jobs[AJIU_BROKER_MAX_JOBS]; initialize_jobs(jobs);
    jobs[0] = (struct broker_job){ .id = 1, .pid = child, .pidfd = pidfd_open_bound(child), .status = status_pipe[1] };
    CHECK(jobs[0].pidfd >= 0);
    int64_t begin = broker_now();
    stop_jobs(jobs); status_pipe[1] = -1;
    CHECK(broker_now() - begin >= AJIU_BROKER_SHUTDOWN_TIMEOUT_MS);
    CHECK(!jobs[0].id);
    uint32_t value = UINT32_MAX;
    CHECK(exit_record(status_pipe[0], &value) == AJIU_BROKER_UNKNOWN);
    int status; CHECK(waitpid(child, &status, WNOHANG) == 0);
    CHECK(kill(child, SIGKILL) == 0);
    CHECK(waitpid(child, &status, 0) == child && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    close_fds(status_pipe, 2);
    CHECK(pipe2(status_pipe, O_CLOEXEC) == 0);
    jobs[0] = (struct broker_job){ .id = 2, .pid = getpid(), .pidfd = -1, .status = status_pipe[1] };
    CHECK(reap_jobs(jobs) == 0); status_pipe[1] = -1;
    CHECK(exit_record(status_pipe[0], &value) == AJIU_BROKER_UNKNOWN && value == 0);
    close_fds(status_pipe, 2);
    CHECK(fd_count() == before); ++cases;
}

static void check_elf_validation(void)
{
    unsigned char bytes[1024] = { 0 };
    Elf64_Ehdr e = { .e_type = ET_DYN, .e_machine = EM_AARCH64, .e_version = EV_CURRENT,
        .e_entry = 320, .e_phoff = sizeof(Elf64_Ehdr), .e_ehsize = sizeof(Elf64_Ehdr),
        .e_phentsize = sizeof(Elf64_Phdr), .e_phnum = 4 };
    memcpy(e.e_ident, ELFMAG, SELFMAG); e.e_ident[EI_CLASS] = ELFCLASS64;
    e.e_ident[EI_DATA] = ELFDATA2LSB; e.e_ident[EI_VERSION] = EV_CURRENT;
    Elf64_Phdr headers[4] = {
        { .p_type = PT_LOAD, .p_flags = PF_R | PF_X, .p_filesz = sizeof(bytes), .p_memsz = sizeof(bytes), .p_align = 16384 },
        { .p_type = PT_GNU_STACK, .p_flags = PF_R | PF_W },
        { .p_type = PT_GNU_RELRO },
        { .p_type = PT_DYNAMIC, .p_offset = 512, .p_filesz = sizeof(Elf64_Dyn) * 2U }
    };
    Elf64_Dyn dynamic[2] = { { .d_tag = DT_FLAGS_1, .d_un.d_val = DF_1_PIE }, { .d_tag = DT_NULL } };
    memcpy(bytes, &e, sizeof(e)); memcpy(bytes + sizeof(e), headers, sizeof(headers)); memcpy(bytes + 512, dynamic, sizeof(dynamic));
    int fd = memfd_create("ajiu-broker-elf-test", MFD_CLOEXEC); CHECK(fd >= 0);
    CHECK(write(fd, bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    struct stat identity; CHECK(fstat(fd, &identity) == 0);
    CHECK(static_elf(fd, &identity) == 0);
    const size_t offsets[] = { 0, EI_CLASS, EI_DATA, EI_VERSION, offsetof(Elf64_Ehdr, e_type),
        offsetof(Elf64_Ehdr, e_machine), offsetof(Elf64_Ehdr, e_version), offsetof(Elf64_Ehdr, e_ehsize),
        offsetof(Elf64_Ehdr, e_phentsize), offsetof(Elf64_Ehdr, e_phnum) + 1,
        sizeof(e) + offsetof(Elf64_Phdr, p_align),
        sizeof(e) + sizeof(Elf64_Phdr) + offsetof(Elf64_Phdr, p_flags),
        512 + offsetof(Elf64_Dyn, d_un) + 3 };
    for (size_t i = 0; i < sizeof(offsets) / sizeof(*offsets); ++i) {
        unsigned char bad[sizeof(bytes)]; memcpy(bad, bytes, sizeof(bad));
        bad[offsets[i]] ^= offsets[i] == 512 + offsetof(Elf64_Dyn, d_un) + 3 ? 8 : 1;
        CHECK(pwrite(fd, bad, sizeof(bad), 0) == (ssize_t)sizeof(bad));
        CHECK(static_elf(fd, &identity) < 0); ++cases;
    }
    const uint32_t invalid_types[] = { PT_INTERP, PT_NULL };
    for (size_t i = 0; i < sizeof(invalid_types) / sizeof(*invalid_types); ++i) {
        CHECK(pwrite(fd, bytes, sizeof(bytes), 0) == (ssize_t)sizeof(bytes));
        CHECK(pwrite(fd, invalid_types + i, sizeof(invalid_types[i]), (off_t)(sizeof(e) + 2 * sizeof(Elf64_Phdr))) == (ssize_t)sizeof(invalid_types[i]));
        CHECK(static_elf(fd, &identity) < 0); ++cases;
    }
    CHECK(pwrite(fd, bytes, sizeof(bytes), 0) == (ssize_t)sizeof(bytes));
    int64_t needed = DT_NEEDED;
    CHECK(pwrite(fd, &needed, sizeof(needed), 512) == (ssize_t)sizeof(needed)); CHECK(static_elf(fd, &identity) < 0);
    CHECK(pwrite(fd, bytes, sizeof(bytes), 0) == (ssize_t)sizeof(bytes));
    uint32_t writable = PF_R | PF_W | PF_X;
    CHECK(pwrite(fd, &writable, sizeof(writable), (off_t)(sizeof(e) + offsetof(Elf64_Phdr, p_flags))) == (ssize_t)sizeof(writable));
    CHECK(static_elf(fd, &identity) < 0);
    CHECK(pwrite(fd, bytes, sizeof(bytes), 0) == (ssize_t)sizeof(bytes));
    uint64_t overflow = UINT64_MAX;
    CHECK(pwrite(fd, &overflow, sizeof(overflow), offsetof(Elf64_Ehdr, e_phoff)) == (ssize_t)sizeof(overflow)); CHECK(static_elf(fd, &identity) < 0);
    CHECK(close(fd) == 0); ++cases;
}

static void check_trusted_files(void)
{
    const struct { mode_t mode; uid_t uid; gid_t gid; bool directory, trusted; } entries[] = {
        { S_IFDIR | 0755, 0, 0, true, true },
        { S_IFDIR | 0755, 1000, 1000, true, true },
        { S_IFDIR | 0771, 1000, 1000, true, true },
        { S_IFDIR | 0770, 0, 0, true, true },
        { S_IFDIR | 0770, 0, 1000, true, true },
        { S_IFDIR | 0770, 1000, 0, true, true },
        { S_IFDIR | 0755, 0, 10000, true, true },
        { S_IFDIR | 0771, 1000, 10000, true, false },
        { S_IFDIR | 0770, 0, 10000, true, false },
        { S_IFDIR | 0755, 10000, 0, true, false },
        { S_IFDIR | 0770, 10000, 1000, true, false },
        { S_IFDIR | 0777, 0, 0, true, false },
        { S_IFDIR | 0773, 1000, 1000, true, false },
        { S_IFREG | 0755, 0, 0, true, false },
        { S_IFLNK | 0770, 0, 0, true, false },
        { S_IFLNK | 0770, 1000, 1000, false, false },
        { S_IFREG | 0755, 0, 0, false, true },
        { S_IFREG | 0755, 1000, 1000, false, true },
        { S_IFREG | 0770, 0, 0, false, false },
        { S_IFREG | 0770, 1000, 1000, false, false },
        { S_IFREG | 0757, 0, 0, false, false },
        { S_IFREG | 0755, 10000, 0, false, false },
        { S_IFDIR | 0755, 0, 0, false, false }
    };
    for (size_t i = 0; i < sizeof(entries) / sizeof(*entries); ++i) {
        struct stat s = { .st_mode = entries[i].mode, .st_uid = entries[i].uid, .st_gid = entries[i].gid };
        CHECK(trusted_stat(&s, entries[i].directory) == entries[i].trusted);
        ++cases;
    }
    struct broker_executable e = fixture_executable();
    CHECK(static_elf(e.fd, &e.identity) < 0);
    CHECK(apk_directory() < 0);
    int null_fd = open("/dev/null", O_RDONLY | O_CLOEXEC); CHECK(null_fd >= 0);
    CHECK(static_elf(null_fd, &e.identity) < 0); CHECK(close(null_fd) == 0); CHECK(close(e.fd) == 0); ++cases;
}

static int fixture_main(int argc, char **argv)
{
    if (getuid() == 0 || geteuid() == 0) return 80;
    DIR *directory = opendir("/proc/self/fd");
    if (!directory) return 81;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        char *end;
        long fd = strtol(entry->d_name, &end, 10);
        if (end != entry->d_name && !*end && fd > 2 && fd != dirfd(directory)) { (void)closedir(directory); return 81; }
    }
    if (closedir(directory) < 0) return 81;
    if (!getenv("PATH") || strcmp(getenv("PATH"), "/system/bin") || !getenv("LANG") || strcmp(getenv("LANG"), "C")) return 82;
    extern char **environ;
    if (!environ[0] || !environ[1] || environ[2]) return 83;
    if (argc == 2 && !strcmp(argv[1], "--runtime")) {
        unsigned char buffer[128];
        for (;;) {
            ssize_t n = read(0, buffer, sizeof(buffer));
            if (!n) break;
            if (n < 0 || write(1, buffer, (size_t)n) != n) return 84;
        }
        if (write(2, "fixture-stderr", 14) != 14) return 85;
        return 37;
    }
    if (argc == 3 && !strcmp(argv[1], "--install") && strlen(argv[2]) == AJIU_BROKER_INSTANCE_SIZE) {
        unsigned char byte;
        while (read(0, &byte, 1) > 0) {}
        return 23;
    }
    if (argc == 5 && !strcmp(argv[1], "--remove") && strlen(argv[2]) == AJIU_BROKER_INSTANCE_SIZE &&
        !strcmp(argv[3], "1234") && !strcmp(argv[4], "5678")) return 24;
    if (argc == 5 && !strcmp(argv[0], AJIU_BROKER_INSTALLER_NAME) && !strcmp(argv[1], "--recover-remove") &&
        !strcmp(argv[2], "aaaaaaaaaaaaaaaaaaaaaaaa") && !strcmp(argv[3], "1234") && !strcmp(argv[4], "5678")) return 25;
    return 86;
}

int main(int argc, char **argv)
{
    if (argc > 1 && (!strcmp(argv[0], AJIU_BROKER_RUNTIME_NAME) || !strcmp(argv[0], AJIU_BROKER_INSTALLER_NAME))) return fixture_main(argc, argv);
    if (getuid() == 0) {
        CHECK(setgroups(0, NULL) == 0);
        CHECK(setgid(1000) == 0);
        CHECK(setuid(1000) == 0);
    }
    CHECK(getuid() != 0 && geteuid() != 0 && getuid() == getgid());
    CHECK(signal(SIGPIPE, SIG_IGN) != SIG_ERR);
#ifdef AJIU_BROKER_TEST_EXEC_FIXTURE
    ssize_t length = readlink("/proc/self/exe", test_fixture_path, sizeof(test_fixture_path) - 1);
    CHECK(length > 0 && length < (ssize_t)sizeof(test_fixture_path) - 1);
    test_fixture_path[length] = '\0';
    puts("TEST FIXTURE EXEC ENABLED: real execve of identity-matched test program only; production execveat execution NOT VERIFIED");
#endif
    check_arguments(); check_requests(); check_wire(); check_peer_and_hello(); check_status_unknown();
    check_trusted_files(); check_elf_validation(); check_spawn(); check_failures(); check_control_loop();
    check_actual_capacity(); check_app_death(); check_suspend_clock(); check_bounded_unknown();
    printf("Native broker: %u cases, %u checks; real socket/SCM_RIGHTS/pipe/waitpid; test uid=%lu; production broker main not called\n", cases, checks, (unsigned long)getuid());
    return 0;
}
