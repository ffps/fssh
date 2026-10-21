/* Minimal GNU-style ls for old Android (toolbox ls prints no "." / ".." and no link count,
 * which breaks WinSCP/NetBox style parsers). Supports -l -a -A -d -1 -h -n -F and --full-time;
 * anything else is handed to the system ls. */
#include <dirent.h>
#include <errno.h>
#include <grp.h>
#include <limits.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

static int f_l, f_a, f_A, f_d, f_h, f_n, f_F, f_full;

struct ent { char *name; char *path; struct stat st; int ok; };

static int cmp(const void *a, const void *b) {
    return strcmp(((const struct ent *)a)->name, ((const struct ent *)b)->name);
}

static void delegate(char **argv) {
    execv("/system/bin/ls", argv);
    fprintf(stderr, "ls: unsupported option\n");
    exit(2);
}

static void modestr(mode_t m, char *s) {
    strcpy(s, "----------");
    if (S_ISDIR(m)) s[0] = 'd'; else if (S_ISLNK(m)) s[0] = 'l'; else if (S_ISCHR(m)) s[0] = 'c';
    else if (S_ISBLK(m)) s[0] = 'b'; else if (S_ISFIFO(m)) s[0] = 'p'; else if (S_ISSOCK(m)) s[0] = 's';
    if (m & S_IRUSR) s[1] = 'r'; if (m & S_IWUSR) s[2] = 'w'; if (m & S_IXUSR) s[3] = 'x';
    if (m & S_IRGRP) s[4] = 'r'; if (m & S_IWGRP) s[5] = 'w'; if (m & S_IXGRP) s[6] = 'x';
    if (m & S_IROTH) s[7] = 'r'; if (m & S_IWOTH) s[8] = 'w'; if (m & S_IXOTH) s[9] = 'x';
    if (m & S_ISUID) s[3] = (m & S_IXUSR) ? 's' : 'S';
    if (m & S_ISGID) s[6] = (m & S_IXGRP) ? 's' : 'S';
    if (m & S_ISVTX) s[9] = (m & S_IXOTH) ? 't' : 'T';
}

static void sizestr(long long v, char *out, size_t n) {
    if (!f_h) { snprintf(out, n, "%lld", v); return; }
    const char *u = "BKMGT"; double d = (double)v; int i = 0;
    while (d >= 1024 && i < 4) { d /= 1024; i++; }
    if (i == 0) snprintf(out, n, "%lld", v);
    else if (d < 10) snprintf(out, n, "%.1f%c", d, u[i]);
    else snprintf(out, n, "%.0f%c", d, u[i]);
}

static void print_ent(struct ent *e) {
    if (!f_l) {
        printf("%s", e->name);
        if (f_F && e->ok) {
            if (S_ISDIR(e->st.st_mode)) putchar('/');
            else if (S_ISLNK(e->st.st_mode)) putchar('@');
            else if (e->st.st_mode & S_IXUSR) putchar('*');
        }
        putchar('\n');
        return;
    }
    if (!e->ok) { printf("?????????? ? ? ? ? ? %s\n", e->name); return; }
    char mode[16], sz[32], tm[64], un[32], gn[32];
    modestr(e->st.st_mode, mode);
    struct passwd *pw = f_n ? NULL : getpwuid(e->st.st_uid);
    struct group *gr = f_n ? NULL : getgrgid(e->st.st_gid);
    if (pw) snprintf(un, sizeof un, "%s", pw->pw_name); else snprintf(un, sizeof un, "%u", (unsigned)e->st.st_uid);
    if (gr) snprintf(gn, sizeof gn, "%s", gr->gr_name); else snprintf(gn, sizeof gn, "%u", (unsigned)e->st.st_gid);
    sizestr((long long)e->st.st_size, sz, sizeof sz);
    time_t t = e->st.st_mtime;
    struct tm *l = localtime(&t);
    if (!l) strcpy(tm, "1970-01-01 00:00");
    else if (f_full) {
        char b[48]; char z[16];
        strftime(b, sizeof b, "%Y-%m-%d %H:%M:%S", l);
        strftime(z, sizeof z, "%z", l);
        snprintf(tm, sizeof tm, "%s.000000000 %s", b, z);
    } else strftime(tm, sizeof tm, "%Y-%m-%d %H:%M", l);
    printf("%s %3lu %-8s %-8s %8s %s %s", mode, (unsigned long)e->st.st_nlink, un, gn, sz, tm, e->name);
    if (S_ISLNK(e->st.st_mode)) {
        char tgt[PATH_MAX]; ssize_t r = readlink(e->path, tgt, sizeof tgt - 1);
        if (r > 0) { tgt[r] = 0; printf(" -> %s", tgt); }
    } else if (f_F) {
        if (S_ISDIR(e->st.st_mode)) putchar('/'); else if (e->st.st_mode & S_IXUSR) putchar('*');
    }
    putchar('\n');
}

static int list_dir(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) { fprintf(stderr, "ls: %s: %s\n", dir, strerror(errno)); return 1; }
    struct ent *v = NULL; size_t n = 0, cap = 0; struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') {
            int dots = !strcmp(de->d_name, ".") || !strcmp(de->d_name, "..");
            if (!f_a && !f_A) continue;
            if (f_A && !f_a && dots) continue;
        }
        if (n == cap) { cap = cap ? cap * 2 : 64; v = realloc(v, cap * sizeof *v); if (!v) exit(2); }
        v[n].name = strdup(de->d_name);
        size_t pl = strlen(dir) + strlen(de->d_name) + 2;
        v[n].path = malloc(pl);
        snprintf(v[n].path, pl, "%s/%s", dir, de->d_name);
        memset(&v[n].st, 0, sizeof v[n].st);
        v[n].ok = (f_l || f_F) ? (lstat(v[n].path, &v[n].st) == 0) : 0;
        n++;
    }
    closedir(d);
    qsort(v, n, sizeof *v, cmp);
    if (f_l) {
        long long blocks = 0;
        for (size_t i = 0; i < n; i++) if (v[i].ok) blocks += (long long)v[i].st.st_blocks;
        printf("total %lld\n", blocks / 2);
    }
    for (size_t i = 0; i < n; i++) print_ent(&v[i]);
    return 0;
}

int main(int argc, char **argv) {
    const char *targets[256]; int nt = 0, end = 0, i;
    for (i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!end && !strcmp(a, "--")) { end = 1; continue; }
        if (!end && a[0] == '-' && a[1]) {
            if (a[1] == '-') {
                if (!strcmp(a, "--full-time")) { f_full = 1; f_l = 1; }
                else if (!strncmp(a, "--color", 7)) { }
                else delegate(argv);
            } else {
                for (const char *c = a + 1; *c; c++) {
                    switch (*c) {
                    case 'l': f_l = 1; break; case 'a': f_a = 1; break; case 'A': f_A = 1; break;
                    case 'd': f_d = 1; break; case '1': break; case 'h': f_h = 1; break;
                    case 'n': f_n = 1; f_l = 1; break; case 'F': f_F = 1; break;
                    default: delegate(argv);
                    }
                }
            }
        } else if (nt < 256) targets[nt++] = a;
    }
    if (nt == 0) targets[nt++] = ".";
    int rc = 0;
    for (i = 0; i < nt; i++) {
        struct stat st;
        if (lstat(targets[i], &st) != 0) { fprintf(stderr, "ls: %s: %s\n", targets[i], strerror(errno)); rc = 1; continue; }
        int isdir = S_ISDIR(st.st_mode);
        if (!isdir && S_ISLNK(st.st_mode) && !f_l && !f_d) { struct stat s2; if (stat(targets[i], &s2) == 0) isdir = S_ISDIR(s2.st_mode); }
        if (isdir && !f_d) {
            if (nt > 1) printf("%s%s:\n", i ? "\n" : "", targets[i]);
            rc |= list_dir(targets[i]);
        } else {
            struct ent e; e.name = (char *)targets[i]; e.path = (char *)targets[i]; e.st = st; e.ok = 1;
            print_ent(&e);
        }
    }
    return rc;
}
