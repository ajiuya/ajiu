#define main probe_entry
#include "cgroup_probe.c"
#undef main

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "Native probe check failed at line %d: %s (errno=%d)\n", __LINE__, #condition, errno); \
        return 1; \
    } \
    ++checks; \
} while (0)

int main(void)
{
    int checks = 0;
    struct config c;
    struct known task;
    memset(&c, 0, sizeof(c));
    memset(&task, 0, sizeof(task));
    task.pid = getpid();
    pid_t parent;
    CHECK(proc_stat(task.pid, &task.start, &parent) == 0);
    char current[TEXT_MAX];
    CHECK(self_cgroup(task.pid, current, sizeof(current)) == 0 && current[0] == '/');
    CHECK(strlen(current + 1) < sizeof(c.name));
    memcpy(c.name, current + 1, strlen(current + 1) + 1);
    CHECK(registered_member(&c, &task, false) == 0);
    ++task.start;
    CHECK(registered_member(&c, &task, false) == -1);
    --task.start;
    (void)snprintf(c.name, sizeof(c.name), "ajiu_probe_000000000000000000000000");
    CHECK(strcmp(current + 1, c.name) != 0);
    CHECK(registered_member(&c, &task, false) == -1);
    struct shared s;
    memset(&s, 0, sizeof(s));
    s.task[0] = task;
    store_u(&s.task[0].accepted, 1);
    CHECK(registered_guard(&c, &s) == -1 && load_u(&s.unsafe) == 1);
    int self_fd = pidfd_open_fixed(getpid());
    CHECK(self_fd >= 0);
    int live[MAX_TASKS] = { self_fd, self_fd, self_fd };
    CHECK(!tasks_exited(live));
    int missing[MAX_TASKS] = { -1, -1, -1 };
    CHECK(!tasks_exited(missing));
    pid_t child = fork();
    CHECK(child >= 0);
    if (child == 0)
        _exit(0);
    int child_fd = pidfd_open_fixed(child);
    CHECK(child_fd >= 0);
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    int exited[MAX_TASKS] = { child_fd, child_fd, child_fd };
    CHECK(tasks_exited(exited));
    CHECK(close(child_fd) == 0);
    CHECK(close(self_fd) == 0);
    printf("Native registered-task checks passed: %d; no cgroup writes or signals.\n", checks);
    return 0;
}
