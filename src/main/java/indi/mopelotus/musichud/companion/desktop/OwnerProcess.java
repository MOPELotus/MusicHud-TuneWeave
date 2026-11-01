package indi.mopelotus.musichud.companion.desktop;

/** A PID alone is insufficient: the operating system may reuse it after the game exits. */
record OwnerProcess(long pid, long startedAtMillis) {
    static OwnerProcess of(Long pid, Long startedAtMillis) {
        if (pid == null && startedAtMillis == null) return null;
        if (pid == null || startedAtMillis == null || pid <= 0 || startedAtMillis <= 0) {
            throw new IllegalArgumentException("--owner-pid and --owner-start must be positive and supplied together");
        }
        return new OwnerProcess(pid, startedAtMillis);
    }

    boolean alive() {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(start -> start.toEpochMilli() == startedAtMillis).orElse(false);
    }
}
