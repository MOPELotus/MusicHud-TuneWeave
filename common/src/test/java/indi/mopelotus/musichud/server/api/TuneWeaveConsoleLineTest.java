package indi.mopelotus.musichud.server.api;
import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import static org.junit.jupiter.api.Assertions.*;
class TuneWeaveConsoleLineTest {
    @Test void explicitSeverityWinsOverPipeAndMessageContents() {
        for (String level : new String[]{"TRACE","DEBUG","INFO","WARN","ERROR"})
            for (boolean stderr : new boolean[]{false,true})
                assertEquals(Level.valueOf(level), TuneWeaveConsoleLine.parse("[TuneWeave]["+level+"][2026-09-29] ERROR is a quoted word",stderr).level());
        assertEquals(Level.INFO,TuneWeaveConsoleLine.parse("[TuneWeave/server][INFO][2026-09-29] ready",true).level());
        assertEquals(Level.WARN,TuneWeaveConsoleLine.parse("[TuneWeave/provider-kuwo][WARN] retry",true).level());
        assertEquals(Level.INFO,TuneWeaveConsoleLine.parse("\u001b[32m[TuneWeave][INFO]\u001b[0m started",true).level());
    }
    @Test void UnknownOutputPreservesErrorsWithoutSearchingMessageForLevels() {
        assertEquals(Level.ERROR,TuneWeaveConsoleLine.parse("panic [INFO] text",true).level());
        assertEquals(Level.INFO,TuneWeaveConsoleLine.parse("output mentions ERROR",false).level());
        assertEquals(Level.INFO,TuneWeaveConsoleLine.parse("[INFO] ready",true).level());
        assertEquals("",TuneWeaveConsoleLine.parse(null,false).text());
    }
}
