package indi.mopelotus.musichud.companion.desktop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerProcessTest {
    @Test void validatesPairAndDetectsPidReuse() {
        var current = ProcessHandle.current();
        long started = current.info().startInstant().orElseThrow().toEpochMilli();
        assertTrue(OwnerProcess.of(current.pid(), started).alive());
        assertFalse(OwnerProcess.of(current.pid(), started + 1).alive());
        assertNull(OwnerProcess.of(null, null));
        assertThrows(IllegalArgumentException.class, () -> OwnerProcess.of(1L, null));
    }
    @Test void observesOwnerExit() throws Exception {
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), SleepingOwner.class.getName()).start();
        try {
            OwnerProcess owner = new OwnerProcess(process.pid(), process.info().startInstant().orElseThrow().toEpochMilli());
            assertTrue(owner.alive());
            process.destroyForcibly().waitFor();
            assertFalse(owner.alive());
        } finally { process.destroyForcibly(); }
    }
    public static class SleepingOwner {
        public static void main(String[] args) throws Exception { Thread.sleep(60_000); }
    }
}
