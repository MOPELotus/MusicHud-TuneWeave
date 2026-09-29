package indi.mopelotus.musichud.server.api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
class SodaSignerInstallerTest {
    @TempDir Path dir;
    @Test void verifiesImmutableBlobAndRejectsMismatchTruncationAndSymlinks() throws Exception {
        byte[] bytes=new byte[64];bytes[0]='M';bytes[1]='Z';Path file=dir.resolve("download");Files.write(file,bytes);
        var digest=MessageDigest.getInstance("SHA-1");digest.update("blob 64\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));digest.update(bytes);
        String sha=HexFormat.of().formatHex(digest.digest());var blob=new SodaSignerInstaller.Blob(sha,64);
        SodaSignerInstaller.verify(file,blob);
        assertThrows(IOException.class,()->SodaSignerInstaller.verify(file,new SodaSignerInstaller.Blob("0".repeat(40),64)));
        Path link=dir.resolve("link");Files.createSymbolicLink(link,file);assertThrows(IOException.class,()->SodaSignerInstaller.verify(link,blob));
        assertThrows(IOException.class,()->SodaSignerInstaller.copy(new ByteArrayInputStream(new byte[63]),new ByteArrayOutputStream(),64,new AtomicBoolean(),null));
        assertThrows(IOException.class,()->SodaSignerInstaller.copy(new ByteArrayInputStream(new byte[65]),new ByteArrayOutputStream(),64,new AtomicBoolean(),null));
        assertThrows(CancellationException.class,()->SodaSignerInstaller.copy(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),64,new AtomicBoolean(true),null));
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void validatesMetadataWithoutTrustingServerDownloadUrl() throws Exception {
        String valid="{\"name\":\"TuneWeave-SodaSigner.exe\",\"type\":\"file\",\"size\":64,\"sha\":\""+"a".repeat(40)+"\"}";
        assertEquals(64,SodaSignerInstaller.parse(valid).size());
        for(String bad:new String[]{"null","{}",valid.replace("64","-1"),valid.replace("64","64.5"),valid.replace("64","999999999999999999999"),valid.replace("file","symlink"),valid.replace("TuneWeave-SodaSigner.exe","../x")})
            assertThrows(IOException.class,()->SodaSignerInstaller.parse(bad));
    }
    @Test void installsNewPairWithoutTouchingRunningOrUnrelatedFiles() throws Exception {
        Path active=dir.resolve("tuneweave.exe");Files.writeString(active,"active");Path unrelated=dir.resolve("notes.txt");Files.writeString(unrelated,"keep");
        String name=".musichud-soda-signer-"+"a".repeat(40)+".exe";Path signer=dir.resolve(name);Files.writeString(signer,"signer");
        var installation=new SodaSignerSupport.Installation(name,SodaSignerSupport.sha256(signer));
        Path temp=dir.resolve("download.temp");Files.writeString(temp,"new");
        var release=new ApiBinaryUpdateService.DownloadedRelease("v1","1",temp,installation);
        Path result=ApiBinaryUpdateService.getInstance().resolveFinalPath(release);
        assertNotNull(result);assertEquals(installation,SodaSignerSupport.read(result));
        assertEquals("active",Files.readString(active));assertEquals("keep",Files.readString(unrelated));assertEquals("signer",Files.readString(signer));
        assertThrows(FileAlreadyExistsException.class,()->SodaSignerSupport.writeNew(result,installation));
        assertThrows(IOException.class,()->SodaSignerSupport.validate(dir,new SodaSignerSupport.Installation("../outside",installation.sha256())));
        Files.writeString(signer,"changed");assertThrows(IOException.class,()->SodaSignerSupport.read(result));
    }
    @Test void rejectsMalformedAndOversizedDescriptorsWithoutReplacingThem() throws Exception {
        Path binary=dir.resolve("tuneweave.exe"), descriptor=SodaSignerSupport.descriptor(binary);
        Files.writeString(binary,"active");
        for (String invalid : new String[]{"null", "{}", "[]", "x".repeat(4097)}) {
            Files.writeString(descriptor,invalid);
            assertThrows(IOException.class,()->SodaSignerSupport.read(binary));
            assertEquals(invalid,Files.readString(descriptor));
            assertEquals("active",Files.readString(binary));
        }
    }
    @Test void pairsLoopbackPortAndTokenWithoutArgumentsOrConsoleOutput() {
        var main=new ProcessBuilder("tuneweave.exe"); var child=new ProcessBuilder("signer.exe");
        SodaSignerSupport.configurePair(main,child,18735,"synthetic-only-token");
        assertEquals("127.0.0.1:18735",child.environment().get("TUNEWEAVE_SODA_BDMS_BIND"));
        assertEquals("http://127.0.0.1:18735",main.environment().get("TUNEWEAVE_SODA_BDMS_SERVICE_URL"));
        assertEquals("synthetic-only-token",main.environment().get("TUNEWEAVE_SODA_BDMS_SERVICE_TOKEN"));
        assertEquals(main.environment().get("TUNEWEAVE_SODA_BDMS_SERVICE_TOKEN"),child.environment().get("TUNEWEAVE_SODA_BDMS_TOKEN"));
        assertEquals(java.util.List.of("tuneweave.exe"),main.command());
        assertEquals(java.util.List.of("signer.exe"),child.command());
        assertEquals(ProcessBuilder.Redirect.DISCARD,child.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.DISCARD,child.redirectError());
    }

}
